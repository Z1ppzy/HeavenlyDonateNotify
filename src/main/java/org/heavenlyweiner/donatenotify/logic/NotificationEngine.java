package org.heavenlyweiner.donatenotify.logic;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.heavenlyweiner.donatenotify.config.ActiveSettings;
import org.heavenlyweiner.donatenotify.model.UserSnapshot;
import org.heavenlyweiner.donatenotify.storage.HistoryStore;
import org.heavenlyweiner.donatenotify.storage.StorageException;
import org.heavenlyweiner.donatenotify.storage.TrackedState;
import org.heavenlyweiner.donatenotify.storage.TrackedSubscription;
import org.slf4j.Logger;

/**
 * Одна проверка игрока по свежему снимку LuckPerms: решает, что отправить, и защищает от повторов.
 *
 * <p>Запись об отправке делается до доставки атомарной вставкой в хранилище: повторный вход,
 * рестарт прокси и параллельные проверки не дают дубликатов. При ошибке хранилища ничего не отправляется.
 */
public final class NotificationEngine {

    public enum Result { NONE, WARNED, EXPIRED_NOTICE, PERMANENT, NO_SPONSOR, REVOKED, UNKNOWN, STORAGE_ERROR, NOT_DELIVERED }

    public record Outcome(Result result, SponsorAccess access, Threshold threshold) {}

    private static final Duration NOTE_LOG_PERIOD = Duration.ofHours(1);
    private static final Duration STORAGE_LOG_PERIOD = Duration.ofMinutes(5);

    private final Supplier<ActiveSettings.Bundle> bundle;
    private final HistoryStore store;
    private final Clock clock;
    private final Notifier notifier;
    private final Logger log;
    private final LogThrottle throttle;
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    public NotificationEngine(Supplier<ActiveSettings.Bundle> bundle, HistoryStore store, Clock clock, Notifier notifier,
                              Logger log) {
        this.bundle = bundle;
        this.store = store;
        this.clock = clock;
        this.notifier = notifier;
        this.log = log;
        this.throttle = new LogThrottle(clock);
    }

    /** Проверки одного игрока выполняются последовательно, даже если запущены из разных потоков. */
    public Outcome check(UserSnapshot user) {
        Object lock = locks.computeIfAbsent(user.uuid(), key -> new Object());
        synchronized (lock) {
            return checkLocked(user);
        }
    }

    private Outcome checkLocked(UserSnapshot user) {
        ActiveSettings.Bundle active = bundle.get();
        Instant now = clock.instant();
        SponsorAccess access = active.evaluator().evaluate(user, now);
        logNotes(user, access);
        String group = active.settings().sponsorGroup();
        try {
            return switch (access.kind()) {
                case UNKNOWN -> new Outcome(Result.UNKNOWN, access, null);
                case PERMANENT -> {
                    store.supersedeActive(user.uuid(), group);
                    yield new Outcome(Result.PERMANENT, access, null);
                }
                case NONE -> afterLoss(active, user, access, now);
                case TEMPORARY -> temporary(active, user, access, now);
            };
        } catch (StorageException e) {
            if (throttle.allow("storage", STORAGE_LOG_PERIOD)) {
                log.error("event=donate_notify_storage_failed error={}: уведомления приостановлены, повторная отправка не выполняется",
                        e.getMessage());
            }
            return new Outcome(Result.STORAGE_ERROR, access, null);
        }
    }

    private Outcome temporary(ActiveSettings.Bundle active, UserSnapshot user, SponsorAccess access, Instant now)
            throws StorageException {
        String group = active.settings().sponsorGroup();
        Instant end = access.end();
        Duration remaining = Duration.between(now, end);
        // LuckPerms ещё не убрал истёкший узел: обрабатываем как окончание.
        if (remaining.isZero() || remaining.isNegative()) return afterLoss(active, user, access, now);
        store.observeActive(user.uuid(), group, end, now);
        Optional<Threshold> due = active.thresholds().due(remaining);
        if (due.isEmpty()) return new Outcome(Result.NONE, access, null);
        if (!store.claim(user.uuid(), group, end, due.get(), now)) return new Outcome(Result.NONE, access, due.get());
        Component message = active.renderer().warning(user.name(), end, now, access.remaining());
        return deliver(user, group, end, due.get(), message, access, Result.WARNED);
    }

    /** Sponsor нет: истёк во время отсутствия (отслеживался) — одно сообщение; снят до срока — ничего. */
    private Outcome afterLoss(ActiveSettings.Bundle active, UserSnapshot user, SponsorAccess access, Instant now)
            throws StorageException {
        String group = active.settings().sponsorGroup();
        List<TrackedSubscription> tracked = store.tracked(user.uuid(), group);
        TrackedSubscription latestExpired = null;
        boolean revoked = false;
        for (TrackedSubscription subscription : tracked) {
            if (subscription.state() != TrackedState.ACTIVE) continue;
            if (subscription.expiry().isAfter(now)) {
                store.setState(user.uuid(), group, subscription.expiry(), TrackedState.REVOKED);
                revoked = true;
                log.info("event=donate_notify_revoked player={} end={}: группа снята до срока, сообщение об окончании не отправляется",
                        user.name(), subscription.expiry());
            } else if (latestExpired == null || subscription.expiry().isAfter(latestExpired.expiry())) {
                latestExpired = subscription;
            }
        }
        if (latestExpired == null) return new Outcome(revoked ? Result.REVOKED : Result.NO_SPONSOR, access, null);
        Instant end = latestExpired.expiry();
        if (!store.claim(user.uuid(), group, end, Threshold.EXPIRED, now)) {
            markExpiredNotified(user.uuid(), group, tracked, now);
            return new Outcome(Result.NONE, access, Threshold.EXPIRED);
        }
        Component message = active.renderer().expired(user.name(), end, now, access.remaining());
        Outcome outcome = deliver(user, group, end, Threshold.EXPIRED, message, access, Result.EXPIRED_NOTICE);
        if (outcome.result() == Result.EXPIRED_NOTICE) markExpiredNotified(user.uuid(), group, tracked, now);
        return outcome;
    }

    private void markExpiredNotified(UUID uuid, String group, List<TrackedSubscription> tracked, Instant now)
            throws StorageException {
        for (TrackedSubscription subscription : tracked) {
            if (subscription.state() == TrackedState.ACTIVE && !subscription.expiry().isAfter(now)) {
                store.setState(uuid, group, subscription.expiry(), TrackedState.EXPIRED_NOTIFIED);
            }
        }
    }

    private Outcome deliver(UserSnapshot user, String group, Instant end, Threshold threshold, Component message,
                            SponsorAccess access, Result success) throws StorageException {
        if (notifier.deliver(user.uuid(), message)) {
            log.info("event=donate_notify_sent player={} threshold={} end={}", user.name(), threshold.token(), end);
            return new Outcome(success, access, threshold);
        }
        // Игрок уже недоступен: запись снимаем, чтобы предупредить при следующей возможности.
        store.release(user.uuid(), group, end, threshold);
        return new Outcome(Result.NOT_DELIVERED, access, threshold);
    }

    private void logNotes(UserSnapshot user, SponsorAccess access) {
        for (String note : access.notes()) {
            if (throttle.allow(user.uuid() + "|" + note, NOTE_LOG_PERIOD)) {
                log.warn("event=donate_notify_skipped player={} detail={}", user.name(), note);
            }
        }
    }
}
