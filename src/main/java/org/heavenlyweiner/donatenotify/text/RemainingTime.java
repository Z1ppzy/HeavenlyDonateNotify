package org.heavenlyweiner.donatenotify.text;

import java.time.Duration;

/** Понятный остаток времени: две старшие единицы, без «0 дней». */
public final class RemainingTime {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private RemainingTime() {}

    /** «2 дня 5 часов», «23 часа 59 минут», «45 минут», «меньше минуты». */
    public static String format(Duration remaining) {
        if (remaining.compareTo(MINUTE) < 0) return "меньше минуты";
        long days = remaining.toDays();
        int hours = remaining.toHoursPart();
        int minutes = remaining.toMinutesPart();
        if (days > 0) return hours > 0 ? Plural.days(days) + " " + Plural.hours(hours) : Plural.days(days);
        if (hours > 0) return minutes > 0 ? Plural.hours(hours) + " " + Plural.minutes(minutes) : Plural.hours(hours);
        return Plural.minutes(minutes);
    }
}
