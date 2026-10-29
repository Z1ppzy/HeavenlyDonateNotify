package org.heavenlyweiner.donatenotify;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.heavenlyweiner.donatenotify.config.ActiveSettings;
import org.heavenlyweiner.donatenotify.logic.LogThrottle;
import org.heavenlyweiner.donatenotify.logic.NotificationEngine;
import org.heavenlyweiner.donatenotify.login.LoginGate;
import org.heavenlyweiner.donatenotify.luckperms.LuckPermsSource;
import org.slf4j.Logger;

/**
 * Единственный рабочий поток плагина: отложенная проверка после входа, проверка игроков онлайн
 * раз в интервал и проверки по событиям LuckPerms. Обработчики событий Velocity только ставят задачи.
 */
public final class CheckScheduler implements AutoCloseable {

    private static final Duration RECALC_DEBOUNCE = Duration.ofSeconds(2);
    private static final Duration RECALC_DELAY = Duration.ofSeconds(1);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(5);
    private static final Duration PROBLEM_LOG_PERIOD = Duration.ofMinutes(5);

    private final ProxyServer server;
    private final Logger log;
    private final ActiveSettings settings;
    private final LuckPermsSource source;
    private final LoginGate gate;
    private final Clock clock;
    private final LogThrottle throttle;
    private final ScheduledExecutorService worker;
    private final Map<UUID, ScheduledFuture<?>> pendingLogin = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRecalc = new ConcurrentHashMap<>();
    private volatile NotificationEngine engine;
    private volatile ScheduledFuture<?> tick;
    private volatile boolean closed;

    public CheckScheduler(ProxyServer server, Logger log, ActiveSettings settings, LuckPermsSource source, LoginGate gate,
                          Clock clock) {
        this.server = server;
        this.log = log;
        this.settings = settings;
        this.source = source;
        this.gate = gate;
        this.clock = clock;
        this.throttle = new LogThrottle(clock);
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "HeavenlyDonateNotify-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void attachEngine(NotificationEngine engine) {
        this.engine = engine;
    }

    public Optional<NotificationEngine> engine() {
        return Optional.ofNullable(engine);
    }

    /** Выполнить задачу в рабочем потоке; исключения попадают в журнал, а не убивают поток. */
    public void execute(Runnable task) {
        if (closed) return;
        worker.execute(() -> guard(task));
    }

    public void schedulePeriodic(Runnable task, Duration period) {
        if (closed) return;
        worker.scheduleWithFixedDelay(() -> guard(task), period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Запустить (или перезапустить после reload) периодическую проверку игроков онлайн. */
    public void startTicks() {
        if (closed) return;
        ScheduledFuture<?> previous = tick;
        if (previous != null) previous.cancel(false);
        long millis = settings.settings().checkInterval().toMillis();
        tick = worker.scheduleWithFixedDelay(() -> guard(this::tickAll), millis, millis, TimeUnit.MILLISECONDS);
    }

    /** Первое подключение к игровому серверу в сессии: проверка с настраиваемой задержкой. */
    public void onFirstServerConnect(Player player) {
        if (closed) return;
        UUID uuid = player.getUniqueId();
        long delay = settings.settings().loginDelay().toMillis();
        ScheduledFuture<?> scheduled = worker.schedule(() -> {
            pendingLogin.remove(uuid);
            guard(() -> checkPlayer(uuid));
        }, delay, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = pendingLogin.put(uuid, scheduled);
        if (previous != null) previous.cancel(false);
    }

    public void onDisconnect(UUID uuid) {
        ScheduledFuture<?> pending = pendingLogin.remove(uuid);
        if (pending != null) pending.cancel(false);
        lastRecalc.remove(uuid);
    }

    /** Пересчёт данных в LuckPerms (продление, снятие, синхронизация): проверка с небольшой задержкой. */
    public void onRecalculate(UUID uuid) {
        if (closed || server.getPlayer(uuid).isEmpty()) return;
        long now = clock.millis();
        Long previous = lastRecalc.get(uuid);
        if (previous != null && now - previous < RECALC_DEBOUNCE.toMillis()) return;
        lastRecalc.put(uuid, now);
        worker.schedule(() -> guard(() -> checkPlayer(uuid)), RECALC_DELAY.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void tickAll() {
        for (Player player : server.getAllPlayers()) checkPlayer(player.getUniqueId());
    }

    /** Одна проверка игрока: только онлайн, на игровом сервере, после входа и по свежим данным LuckPerms. */
    public Optional<NotificationEngine.Outcome> checkPlayer(UUID uuid) {
        NotificationEngine current = engine;
        if (current == null) return Optional.empty();
        Player player = server.getPlayer(uuid).orElse(null);
        if (player == null) return Optional.empty();
        LoginGate.Verdict verdict = gate.verdict(player);
        if (verdict == LoginGate.Verdict.AUTH_UNAVAILABLE && throttle.allow("auth", PROBLEM_LOG_PERIOD)) {
            log.warn("event=donate_notify_auth_unavailable: LimboAuth не готов или отсутствует, уведомления не отправляются");
        }
        if (verdict != LoginGate.Verdict.ALLOW) return Optional.empty();
        LuckPermsSource.Result data = source.snapshot(player.getUniqueId(), player.getUsername());
        if (!data.ok()) {
            // Данных нет — ничего не угадываем и не рассылаем.
            if (throttle.allow("luckperms|" + data.error(), PROBLEM_LOG_PERIOD)) {
                log.warn("event=donate_notify_data_unavailable player={} reason={}", player.getUsername(), data.error());
            }
            return Optional.empty();
        }
        return Optional.of(current.check(data.snapshot()));
    }

    private void guard(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("event=donate_notify_task_failed error_type={} message={}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) worker.shutdownNow();
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
