package org.heavenlyweiner.donatenotify.logic;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.heavenlyweiner.donatenotify.text.DurationParser;
import org.heavenlyweiner.donatenotify.text.Plural;

/**
 * Порог предупреждения: секунды до окончания и запись из конфига («24h» остаётся «24h», а не «1d»).
 * Равенство и порядок — только по секундам; {@link #EXPIRED} — само окончание.
 */
public record Threshold(long seconds, String token) implements Comparable<Threshold> {

    public static final String EXPIRED_TOKEN = "expired";
    public static final Threshold EXPIRED = new Threshold(0, EXPIRED_TOKEN);

    private static final Pattern SINGLE_UNIT = Pattern.compile("(\\d{1,9})([dhms])");
    private static final long DAY = Duration.ofDays(1).toSeconds();
    private static final long HOUR = Duration.ofHours(1).toSeconds();
    private static final long MINUTE = Duration.ofMinutes(1).toSeconds();

    public Threshold {
        if (seconds < 0) throw new IllegalArgumentException("порог не может быть отрицательным");
        if (token == null || token.isBlank()) {
            token = seconds == 0 ? EXPIRED_TOKEN : DurationParser.token(Duration.ofSeconds(seconds));
        }
    }

    public static Threshold of(Duration duration) {
        return new Threshold(duration.toSeconds(), null);
    }

    public static Threshold ofSeconds(long seconds) {
        return new Threshold(seconds, null);
    }

    /** Из записи конфига или команды; бросает {@link IllegalArgumentException} на мусор. */
    public static Threshold parse(String token) {
        Duration duration = DurationParser.parse(token);
        return new Threshold(duration.toSeconds(), token.trim().toLowerCase(Locale.ROOT).replace(" ", ""));
    }

    public Duration duration() {
        return Duration.ofSeconds(seconds);
    }

    public boolean isExpiry() {
        return seconds == 0;
    }

    /** «3 дня», «24 часа», «1 час», «истечение» — в единицах записи конфига. */
    public String label() {
        if (seconds == 0) return "истечение";
        Matcher single = SINGLE_UNIT.matcher(token);
        if (single.matches()) return unitLabel(Long.parseLong(single.group(1)), single.group(2).charAt(0));
        if (seconds % DAY == 0) return Plural.days(seconds / DAY);
        if (seconds % HOUR == 0) return Plural.hours(seconds / HOUR);
        if (seconds % MINUTE == 0) return Plural.minutes(seconds / MINUTE);
        return Plural.seconds(seconds);
    }

    private static String unitLabel(long amount, char unit) {
        return switch (unit) {
            case 'd' -> Plural.days(amount);
            case 'h' -> Plural.hours(amount);
            case 'm' -> Plural.minutes(amount);
            default -> Plural.seconds(amount);
        };
    }

    @Override
    public int compareTo(Threshold other) {
        return Long.compare(seconds, other.seconds);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Threshold threshold && threshold.seconds == seconds;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(seconds);
    }
}
