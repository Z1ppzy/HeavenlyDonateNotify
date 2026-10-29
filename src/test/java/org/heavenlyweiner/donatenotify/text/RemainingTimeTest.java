package org.heavenlyweiner.donatenotify.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RemainingTimeTest {

    @Test
    void two_largest_units_without_zero_days() {
        assertEquals("2 дня 5 часов", RemainingTime.format(Duration.ofDays(2).plusHours(5).plusMinutes(7)));
        assertEquals("3 дня", RemainingTime.format(Duration.ofDays(3)));
        assertEquals("5 часов", RemainingTime.format(Duration.ofHours(5)));
        assertEquals("23 часа 59 минут", RemainingTime.format(Duration.ofHours(23).plusMinutes(59).plusSeconds(59)));
        assertEquals("1 час", RemainingTime.format(Duration.ofHours(1)));
        assertEquals("45 минут", RemainingTime.format(Duration.ofMinutes(45).plusSeconds(30)));
        assertEquals("меньше минуты", RemainingTime.format(Duration.ofSeconds(30)));
        assertEquals("меньше минуты", RemainingTime.format(Duration.ZERO));
    }

    @Test
    void russian_plural_forms() {
        assertEquals("1 день", Plural.days(1));
        assertEquals("2 дня", Plural.days(2));
        assertEquals("5 дней", Plural.days(5));
        assertEquals("11 дней", Plural.days(11));
        assertEquals("21 день", Plural.days(21));
        assertEquals("22 дня", Plural.days(22));
        assertEquals("111 дней", Plural.days(111));
        assertEquals("24 часа", Plural.hours(24));
        assertEquals("1 минута", Plural.minutes(1));
        assertEquals("1 день 1 час", RemainingTime.format(Duration.ofDays(1).plusHours(1)));
    }

    @Test
    void duration_tokens_round_trip() {
        assertEquals(Duration.ofDays(3), DurationParser.parse("3d"));
        assertEquals(Duration.ofHours(24), DurationParser.parse("24h"));
        assertEquals(Duration.ofMinutes(90), DurationParser.parse("1h30m"));
        assertEquals(Duration.ofSeconds(90), DurationParser.parse("90s"));
        assertEquals(Duration.ofSeconds(60), DurationParser.parse("60"));
        assertEquals("3d", DurationParser.token(Duration.ofDays(3)));
        assertEquals("1d", DurationParser.token(Duration.ofHours(24)));
        assertEquals("25h", DurationParser.token(Duration.ofHours(25)));
        assertEquals("90m", DurationParser.token(Duration.ofMinutes(90)));
        assertEquals("45s", DurationParser.token(Duration.ofSeconds(45)));
        assertEquals("0s", DurationParser.token(Duration.ZERO));
    }

    @Test
    void invalid_durations_are_rejected() {
        for (String bad : new String[] {"", "  ", "3x", "-5", "h", "1.5h"}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(bad), bad);
        }
    }
}
