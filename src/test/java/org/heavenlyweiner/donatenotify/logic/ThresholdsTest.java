package org.heavenlyweiner.donatenotify.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ThresholdsTest {

    private final Thresholds thresholds = Thresholds.parse(List.of("24h", "3d", "1h"));

    @Test
    void sorted_descending_and_described() {
        assertEquals("3d, 24h, 1h", thresholds.describe());
        assertEquals(List.of(Threshold.of(Duration.ofDays(3)), Threshold.of(Duration.ofHours(24)), Threshold.of(Duration.ofHours(1))),
                thresholds.list());
    }

    @Test
    void due_is_smallest_crossed_threshold_with_inclusive_boundary() {
        assertEquals(Optional.empty(), thresholds.due(Duration.ofDays(4)));
        assertEquals(Optional.empty(), thresholds.due(Duration.ofDays(3).plusSeconds(1)));
        assertEquals(Optional.of(Threshold.of(Duration.ofDays(3))), thresholds.due(Duration.ofDays(3)));
        assertEquals(Optional.of(Threshold.of(Duration.ofDays(3))), thresholds.due(Duration.ofHours(24).plusSeconds(1)));
        assertEquals(Optional.of(Threshold.of(Duration.ofHours(24))), thresholds.due(Duration.ofHours(24)));
        assertEquals(Optional.of(Threshold.of(Duration.ofHours(24))), thresholds.due(Duration.ofHours(1).plusSeconds(1)));
        assertEquals(Optional.of(Threshold.of(Duration.ofHours(1))), thresholds.due(Duration.ofHours(1)));
        assertEquals(Optional.of(Threshold.of(Duration.ofHours(1))), thresholds.due(Duration.ofMinutes(10)));
        assertEquals(Optional.empty(), thresholds.due(Duration.ZERO));
        assertEquals(Optional.empty(), thresholds.due(Duration.ofMinutes(-5)));
    }

    @Test
    void find_by_token_matches_equal_durations_and_expiry() {
        assertEquals(Optional.of(Threshold.of(Duration.ofHours(24))), thresholds.find("24h"));
        assertEquals(Optional.of(Threshold.of(Duration.ofHours(24))), thresholds.find("1d"));
        assertEquals(Optional.of(Threshold.EXPIRED), thresholds.find("expired"));
        assertEquals(Optional.of(Threshold.EXPIRED), thresholds.find("истечение"));
        assertEquals(Optional.empty(), thresholds.find("2h"));
        assertEquals(Optional.empty(), thresholds.find("junk"));
    }

    @Test
    void labels_and_tokens() {
        assertEquals("3 дня", Threshold.of(Duration.ofDays(3)).label());
        assertEquals("24 часа", Threshold.parse("24h").label());
        assertEquals("1 день", Threshold.of(Duration.ofHours(24)).label());
        assertEquals("1 час", Threshold.of(Duration.ofHours(1)).label());
        assertEquals("90 минут", Threshold.of(Duration.ofMinutes(90)).label());
        assertEquals("45 секунд", Threshold.of(Duration.ofSeconds(45)).label());
        assertEquals("истечение", Threshold.EXPIRED.label());
        assertEquals("expired", Threshold.EXPIRED.token());
        assertEquals("3d", Threshold.of(Duration.ofDays(3)).token());
        assertEquals("24h", Threshold.parse("24h").token());
        assertEquals(Threshold.parse("24h"), Threshold.parse("1d"));
        assertEquals("24 часа", thresholds.label(Threshold.ofSeconds(86_400)));
        assertTrue(Threshold.EXPIRED.isExpiry());
    }

    @Test
    void invalid_sets_are_rejected() {
        assertThrows(IllegalArgumentException.class, () -> Thresholds.of(List.of()));
        assertThrows(IllegalArgumentException.class, () -> Thresholds.of(List.of(Duration.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> Thresholds.of(List.of(Duration.ofHours(24), Duration.ofDays(1))));
        assertThrows(IllegalArgumentException.class, () -> Thresholds.parse(List.of("24h", "1d")));
        assertThrows(IllegalArgumentException.class, () -> Thresholds.parse(List.of("soon")));
        assertThrows(IllegalArgumentException.class, () -> Threshold.ofSeconds(-1));
    }
}
