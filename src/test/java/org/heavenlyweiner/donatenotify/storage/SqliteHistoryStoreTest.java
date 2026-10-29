package org.heavenlyweiner.donatenotify.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.heavenlyweiner.donatenotify.logic.Threshold;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteHistoryStoreTest {

    private static final UUID PLAYER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final Instant END = Instant.parse("2026-11-15T15:30:00Z");
    private static final Threshold T24H = Threshold.of(Duration.ofHours(24));

    @TempDir Path directory;
    SqliteHistoryStore store;

    @BeforeEach
    void open() throws Exception {
        store = new SqliteHistoryStore(directory.resolve("history.db"));
    }

    @AfterEach
    void close() throws Exception {
        store.close();
    }

    @Test
    void claim_is_atomic_and_idempotent() throws Exception {
        assertTrue(store.claim(PLAYER, "sponsor", END, T24H, NOW));
        assertFalse(store.claim(PLAYER, "sponsor", END, T24H, NOW.plusSeconds(60)));
        assertTrue(store.claim(PLAYER, "sponsor", END, Threshold.EXPIRED, NOW));
        assertTrue(store.claim(PLAYER, "sponsor", END.plusSeconds(1), T24H, NOW));
        assertTrue(store.claim(PLAYER, "vip", END, T24H, NOW));
        List<SentRecord> sent = store.sent(PLAYER, "sponsor");
        assertEquals(3, sent.size());
        assertEquals(NOW, sent.get(0).sentAt());
        store.release(PLAYER, "sponsor", END, T24H);
        assertTrue(store.claim(PLAYER, "sponsor", END, T24H, NOW));
    }

    @Test
    void observe_active_supersedes_other_terms_and_keeps_single_row_per_term() throws Exception {
        store.observeActive(PLAYER, "sponsor", END, NOW);
        store.observeActive(PLAYER, "sponsor", END, NOW.plusSeconds(60));
        List<TrackedSubscription> tracked = store.tracked(PLAYER, "sponsor");
        assertEquals(1, tracked.size());
        assertEquals(NOW, tracked.get(0).firstSeen());
        assertEquals(NOW.plusSeconds(60), tracked.get(0).lastSeen());
        assertEquals(TrackedState.ACTIVE, tracked.get(0).state());

        Instant extended = END.plus(Duration.ofDays(30));
        store.observeActive(PLAYER, "sponsor", extended, NOW.plusSeconds(120));
        tracked = store.tracked(PLAYER, "sponsor");
        assertEquals(2, tracked.size());
        assertEquals(extended, tracked.get(0).expiry());
        assertEquals(TrackedState.ACTIVE, tracked.get(0).state());
        assertEquals(TrackedState.SUPERSEDED, tracked.get(1).state());

        store.supersedeActive(PLAYER, "sponsor");
        assertTrue(store.tracked(PLAYER, "sponsor").stream().allMatch(t -> t.state() == TrackedState.SUPERSEDED));
        store.setState(PLAYER, "sponsor", END, TrackedState.REVOKED);
        assertEquals(TrackedState.REVOKED, store.tracked(PLAYER, "sponsor").get(1).state());
    }

    @Test
    void prune_removes_old_terms_only() throws Exception {
        Instant old = END.minus(Duration.ofDays(200));
        store.claim(PLAYER, "sponsor", old, T24H, NOW);
        store.observeActive(PLAYER, "sponsor", old, NOW);
        store.claim(PLAYER, "sponsor", END, T24H, NOW);
        store.observeActive(PLAYER, "sponsor", END, NOW);
        assertEquals(2, store.prune(END.minus(Duration.ofDays(90))));
        assertEquals(1, store.sent(PLAYER, "sponsor").size());
        assertEquals(1, store.tracked(PLAYER, "sponsor").size());
        assertEquals(0, store.prune(END.minus(Duration.ofDays(90))));
    }

    @Test
    void data_survives_reopen() throws Exception {
        store.claim(PLAYER, "sponsor", END, T24H, NOW);
        store.observeActive(PLAYER, "sponsor", END, NOW);
        store.close();
        store = new SqliteHistoryStore(directory.resolve("history.db"));
        assertFalse(store.claim(PLAYER, "sponsor", END, T24H, NOW));
        assertEquals(1, store.tracked(PLAYER, "sponsor").size());
    }
}
