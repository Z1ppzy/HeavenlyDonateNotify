package org.heavenlyweiner.donatenotify.logic;

import static org.heavenlyweiner.donatenotify.support.Snapshots.END;
import static org.heavenlyweiner.donatenotify.support.Snapshots.PLAYER;
import static org.heavenlyweiner.donatenotify.support.Snapshots.defaultGroup;
import static org.heavenlyweiner.donatenotify.support.Snapshots.of;
import static org.heavenlyweiner.donatenotify.support.Snapshots.sponsorUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.heavenlyweiner.donatenotify.config.ActiveSettings;
import org.heavenlyweiner.donatenotify.config.SettingsLoader;
import org.heavenlyweiner.donatenotify.logic.NotificationEngine.Outcome;
import org.heavenlyweiner.donatenotify.logic.NotificationEngine.Result;
import org.heavenlyweiner.donatenotify.model.Assignment;
import org.heavenlyweiner.donatenotify.model.UserSnapshot;
import org.heavenlyweiner.donatenotify.storage.HistoryStore;
import org.heavenlyweiner.donatenotify.storage.SentRecord;
import org.heavenlyweiner.donatenotify.storage.SqliteHistoryStore;
import org.heavenlyweiner.donatenotify.storage.StorageException;
import org.heavenlyweiner.donatenotify.storage.TrackedState;
import org.heavenlyweiner.donatenotify.storage.TrackedSubscription;
import org.heavenlyweiner.donatenotify.support.RecordingNotifier;
import org.heavenlyweiner.donatenotify.support.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class NotificationEngineTest {

    private static final String GROUP = "sponsor";
    private static final Threshold T3D = Threshold.of(Duration.ofDays(3));
    private static final Threshold T24H = Threshold.of(Duration.ofHours(24));
    private static final Threshold T1H = Threshold.of(Duration.ofHours(1));

    @TempDir Path directory;
    TestClock clock = new TestClock(END.minus(Duration.ofDays(10)));
    RecordingNotifier notifier = new RecordingNotifier();
    ActiveSettings settings;
    SqliteHistoryStore store;
    NotificationEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        // Сценарии написаны под пороги 3d/24h/1h; значения по умолчанию в config.yml могут отличаться.
        settings = new ActiveSettings(SettingsLoader.parse("thresholds: [3d, 24h, 1h]\n"));
        store = new SqliteHistoryStore(directory.resolve("history.db"));
        engine = engine(store);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    private NotificationEngine engine(HistoryStore target) {
        return new NotificationEngine(settings::current, target, clock, notifier, LoggerFactory.getLogger("test"));
    }

    private Outcome checkAt(Instant now, UserSnapshot snapshot) {
        clock.set(now);
        return engine.check(snapshot);
    }

    private static UserSnapshot sponsor(Instant end) {
        return of(defaultGroup(), sponsorUntil(end));
    }

    private static UserSnapshot noSponsor() {
        return of(defaultGroup());
    }

    @Test
    void warns_once_per_threshold_in_sequence() {
        assertEquals(Result.NONE, checkAt(END.minus(Duration.ofDays(3)).minusSeconds(1), sponsor(END)).result());
        assertEquals(0, notifier.messages.size());
        Outcome first = checkAt(END.minus(Duration.ofDays(3)), sponsor(END));
        assertEquals(Result.WARNED, first.result());
        assertEquals(T3D, first.threshold());
        assertTrue(notifier.last().contains("истечёт через 3 дня."), notifier.last());
        assertTrue(notifier.last().contains("Окончание: 15 ноября в 18:30 МСК."), notifier.last());
        assertEquals(Result.NONE, checkAt(END.minus(Duration.ofDays(2)), sponsor(END)).result());
        assertEquals(Result.NONE, checkAt(END.minus(Duration.ofHours(24).plusSeconds(1)), sponsor(END)).result());
        assertEquals(T24H, checkAt(END.minus(Duration.ofHours(24)), sponsor(END)).threshold());
        assertTrue(notifier.last().contains("через 1 день."), notifier.last());
        assertEquals(Result.NONE, checkAt(END.minus(Duration.ofHours(5)), sponsor(END)).result());
        assertEquals(T1H, checkAt(END.minus(Duration.ofHours(1)), sponsor(END)).threshold());
        assertEquals(Result.NONE, checkAt(END.minus(Duration.ofMinutes(10)), sponsor(END)).result());
        assertEquals(3, notifier.messages.size());
    }

    @Test
    void missed_thresholds_send_only_the_most_relevant() {
        Outcome outcome = checkAt(END.minus(Duration.ofMinutes(50)), sponsor(END));
        assertEquals(Result.WARNED, outcome.result());
        assertEquals(T1H, outcome.threshold());
        assertEquals(Result.NONE, checkAt(END.minus(Duration.ofMinutes(40)), sponsor(END)).result());
        assertEquals(1, notifier.messages.size());
        assertTrue(notifier.last().contains("через 50 минут."), notifier.last());
    }

    @Test
    void login_after_missed_thresholds_then_last_threshold_still_comes() {
        assertEquals(T24H, checkAt(END.minus(Duration.ofHours(20)), sponsor(END)).threshold());
        assertEquals(Result.NONE, checkAt(END.minus(Duration.ofHours(19)), sponsor(END)).result());
        assertEquals(T1H, checkAt(END.minus(Duration.ofMinutes(30)), sponsor(END)).threshold());
        assertEquals(2, notifier.messages.size());
    }

    @Test
    void extension_starts_fresh_tracking_without_stale_or_expiry_messages() throws Exception {
        assertEquals(T24H, checkAt(END.minus(Duration.ofHours(20)), sponsor(END)).threshold());
        Instant extended = END.plus(Duration.ofDays(30));
        assertEquals(Result.NONE, checkAt(END.minus(Duration.ofHours(19)), sponsor(extended)).result());
        List<TrackedSubscription> tracked = store.tracked(PLAYER, GROUP);
        assertEquals(2, tracked.size());
        assertEquals(TrackedState.ACTIVE, stateOf(tracked, extended));
        assertEquals(TrackedState.SUPERSEDED, stateOf(tracked, END));
        assertEquals(Result.NONE, checkAt(END.plus(Duration.ofDays(1)), sponsor(extended)).result());
        assertEquals(1, notifier.messages.size());
        Outcome later = checkAt(extended.minus(Duration.ofDays(2)), sponsor(extended));
        assertEquals(T3D, later.threshold());
        assertTrue(notifier.last().contains("через 2 дня."), notifier.last());
    }

    @Test
    void permanent_sponsor_never_notifies() {
        UserSnapshot permanent = of(defaultGroup(), Assignment.permanent("sponsor"), sponsorUntil(END));
        assertEquals(Result.PERMANENT, checkAt(END.minus(Duration.ofHours(1)), permanent).result());
        UserSnapshot viaParent = of(defaultGroup(), Assignment.permanent("vip"), sponsorUntil(END));
        assertEquals(Result.PERMANENT, checkAt(END.minus(Duration.ofMinutes(5)), viaParent).result());
        assertEquals(Result.NO_SPONSOR, checkAt(END.plus(Duration.ofDays(1)), noSponsor()).result());
        assertTrue(notifier.messages.isEmpty());
    }

    @Test
    void remaining_premium_only_when_independent_and_permanent() {
        checkAt(END.minus(Duration.ofHours(2)), of(defaultGroup(), sponsorUntil(END), Assignment.permanent("premium")));
        assertTrue(notifier.last().contains("После окончания у вас останется Premium."), notifier.last());
        notifier.messages.clear();
        Instant otherEnd = END.plus(Duration.ofDays(1));
        checkAt(otherEnd.minus(Duration.ofHours(2)), of(defaultGroup(), sponsorUntil(otherEnd)));
        assertFalse(notifier.last().contains("Premium"), notifier.last());
        assertTrue(notifier.last().contains("обычным игроком"), notifier.last());
        notifier.messages.clear();
        Instant staffEnd = END.plus(Duration.ofDays(2));
        checkAt(staffEnd.minus(Duration.ofHours(2)), of(defaultGroup(), sponsorUntil(staffEnd), Assignment.permanent("admin")));
        assertTrue(notifier.last().contains("возможности Sponsor станут недоступны"), notifier.last());
    }

    @Test
    void expired_while_offline_sends_single_notice_when_tracked() throws Exception {
        assertEquals(T3D, checkAt(END.minus(Duration.ofDays(2)), sponsor(END)).threshold());
        Outcome expired = checkAt(END.plus(Duration.ofDays(1)), noSponsor());
        assertEquals(Result.EXPIRED_NOTICE, expired.result());
        assertTrue(notifier.last().contains("Срок вашего Sponsor истёк."), notifier.last());
        assertTrue(notifier.last().contains("Теперь вы обычный игрок."), notifier.last());
        assertEquals(Result.NO_SPONSOR, checkAt(END.plus(Duration.ofDays(2)), noSponsor()).result());
        assertEquals(TrackedState.EXPIRED_NOTIFIED, stateOf(store.tracked(PLAYER, GROUP), END));
        assertEquals(2, notifier.messages.size());
    }

    @Test
    void expired_without_tracking_sends_nothing() {
        assertEquals(Result.NO_SPONSOR, checkAt(END.plus(Duration.ofDays(1)), noSponsor()).result());
        assertTrue(notifier.messages.isEmpty());
    }

    @Test
    void node_still_present_after_expiry_is_handled_as_expiry_once() {
        checkAt(END.minus(Duration.ofDays(2)), sponsor(END));
        assertEquals(Result.EXPIRED_NOTICE, checkAt(END.plus(Duration.ofMinutes(1)), sponsor(END)).result());
        assertEquals(Result.NO_SPONSOR, checkAt(END.plus(Duration.ofMinutes(2)), sponsor(END)).result());
        assertEquals(Result.NO_SPONSOR, checkAt(END.plus(Duration.ofMinutes(3)), noSponsor()).result());
        assertEquals(2, notifier.messages.size());
    }

    @Test
    void manual_removal_before_end_sends_no_expiry_notice() throws Exception {
        checkAt(END.minus(Duration.ofDays(2)), sponsor(END));
        assertEquals(Result.REVOKED, checkAt(END.minus(Duration.ofDays(1)), noSponsor()).result());
        assertEquals(TrackedState.REVOKED, stateOf(store.tracked(PLAYER, GROUP), END));
        assertEquals(Result.NO_SPONSOR, checkAt(END.plus(Duration.ofDays(1)), noSponsor()).result());
        assertEquals(1, notifier.messages.size());
    }

    @Test
    void repurchase_after_expiry_supersedes_old_term_without_expiry_notice() throws Exception {
        checkAt(END.minus(Duration.ofDays(2)), sponsor(END));
        Instant renewed = END.plus(Duration.ofDays(31));
        assertEquals(Result.NONE, checkAt(END.plus(Duration.ofDays(1)), sponsor(renewed)).result());
        assertEquals(TrackedState.SUPERSEDED, stateOf(store.tracked(PLAYER, GROUP), END));
        assertEquals(1, notifier.messages.size());
    }

    @Test
    void restart_does_not_resend() throws Exception {
        assertEquals(T3D, checkAt(END.minus(Duration.ofDays(2)), sponsor(END)).threshold());
        store.close();
        store = new SqliteHistoryStore(directory.resolve("history.db"));
        engine = engine(store);
        assertEquals(Result.NONE, checkAt(END.minus(Duration.ofDays(2)).plus(Duration.ofMinutes(1)), sponsor(END)).result());
        assertEquals(1, notifier.messages.size());
        List<SentRecord> sent = store.sent(PLAYER, GROUP);
        assertEquals(1, sent.size());
        assertEquals(T3D, sent.get(0).threshold());
    }

    @Test
    void concurrent_checks_on_one_engine_send_once() throws Exception {
        clock.set(END.minus(Duration.ofHours(20)));
        List<Outcome> outcomes = runConcurrently(() -> engine.check(sponsor(END)), () -> engine.check(sponsor(END)));
        assertEquals(1, notifier.messages.size());
        assertEquals(1, outcomes.stream().filter(o -> o.result() == Result.WARNED).count());
    }

    @Test
    void concurrent_checks_through_separate_connections_send_once() throws Exception {
        clock.set(END.minus(Duration.ofHours(20)));
        try (SqliteHistoryStore second = new SqliteHistoryStore(directory.resolve("history.db"))) {
            NotificationEngine other = engine(second);
            List<Outcome> outcomes = runConcurrently(() -> engine.check(sponsor(END)), () -> other.check(sponsor(END)));
            assertEquals(1, notifier.messages.size());
            assertEquals(1, outcomes.stream().filter(o -> o.result() == Result.WARNED).count());
        }
    }

    @Test
    void storage_failure_sends_nothing_and_never_loops() {
        FailingStore failing = new FailingStore(store);
        engine = engine(failing);
        failing.fail = true;
        for (int i = 0; i < 5; i++) {
            assertEquals(Result.STORAGE_ERROR, checkAt(END.minus(Duration.ofHours(20)).plusSeconds(i), sponsor(END)).result());
        }
        assertTrue(notifier.messages.isEmpty());
        failing.fail = false;
        assertEquals(Result.WARNED, checkAt(END.minus(Duration.ofHours(19)), sponsor(END)).result());
        assertEquals(1, notifier.messages.size());
    }

    @Test
    void undelivered_warning_is_retried_later() throws Exception {
        notifier.online = false;
        assertEquals(Result.NOT_DELIVERED, checkAt(END.minus(Duration.ofHours(20)), sponsor(END)).result());
        assertTrue(store.sent(PLAYER, GROUP).isEmpty());
        notifier.online = true;
        assertEquals(Result.WARNED, checkAt(END.minus(Duration.ofHours(19)), sponsor(END)).result());
        assertEquals(1, notifier.messages.size());
    }

    @Test
    void ambiguous_assignments_do_nothing() throws Exception {
        UserSnapshot unknown = of(defaultGroup(), sponsorUntil(END), new Assignment("sponsor", null, java.util.Map.of("server", java.util.Set.of("survival"))));
        assertEquals(Result.UNKNOWN, checkAt(END.minus(Duration.ofHours(1)), unknown).result());
        assertTrue(store.tracked(PLAYER, GROUP).isEmpty());
        assertTrue(notifier.messages.isEmpty());
    }

    private static TrackedState stateOf(List<TrackedSubscription> tracked, Instant expiry) {
        return tracked.stream().filter(t -> t.expiry().equals(expiry)).findFirst().orElseThrow().state();
    }

    private static List<Outcome> runConcurrently(java.util.function.Supplier<Outcome> first, java.util.function.Supplier<Outcome> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Outcome> a = pool.submit(() -> { start.await(); return first.get(); });
            Future<Outcome> b = pool.submit(() -> { start.await(); return second.get(); });
            start.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Хранилище, которое по флагу отказывает в записи отправки. */
    private static final class FailingStore implements HistoryStore {
        private final HistoryStore delegate;
        volatile boolean fail;

        FailingStore(HistoryStore delegate) {
            this.delegate = delegate;
        }

        @Override public boolean claim(UUID uuid, String group, Instant expiry, Threshold threshold, Instant now) throws StorageException {
            if (fail) throw new StorageException("диск недоступен", null);
            return delegate.claim(uuid, group, expiry, threshold, now);
        }

        @Override public void release(UUID uuid, String group, Instant expiry, Threshold threshold) throws StorageException {
            delegate.release(uuid, group, expiry, threshold);
        }

        @Override public List<SentRecord> sent(UUID uuid, String group) throws StorageException {
            return delegate.sent(uuid, group);
        }

        @Override public void observeActive(UUID uuid, String group, Instant expiry, Instant now) throws StorageException {
            delegate.observeActive(uuid, group, expiry, now);
        }

        @Override public void supersedeActive(UUID uuid, String group) throws StorageException {
            delegate.supersedeActive(uuid, group);
        }

        @Override public List<TrackedSubscription> tracked(UUID uuid, String group) throws StorageException {
            return delegate.tracked(uuid, group);
        }

        @Override public void setState(UUID uuid, String group, Instant expiry, TrackedState state) throws StorageException {
            delegate.setState(uuid, group, expiry, state);
        }

        @Override public int prune(Instant olderThan) throws StorageException {
            return delegate.prune(olderThan);
        }

        @Override public void close() {}
    }
}
