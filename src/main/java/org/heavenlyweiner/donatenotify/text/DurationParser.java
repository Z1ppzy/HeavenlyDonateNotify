package org.heavenlyweiner.donatenotify.text;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Длительности конфига: «3d», «24h», «1h30m», «90s»; число без единицы — секунды. */
public final class DurationParser {

    private static final Pattern PART = Pattern.compile("(\\d{1,9})\\s*([dhms])");
    private static final Pattern WHOLE = Pattern.compile("(?:\\s*\\d{1,9}\\s*[dhms])+\\s*");
    private static final Pattern PLAIN_SECONDS = Pattern.compile("\\d{1,9}");
    private static final long DAY = Duration.ofDays(1).toSeconds();
    private static final long HOUR = Duration.ofHours(1).toSeconds();
    private static final long MINUTE = Duration.ofMinutes(1).toSeconds();

    private DurationParser() {}

    public static Duration parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("пустая длительность");
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (PLAIN_SECONDS.matcher(text).matches()) return Duration.ofSeconds(Long.parseLong(text));
        if (!WHOLE.matcher(text).matches()) {
            throw new IllegalArgumentException("ожидалась длительность вида 3d, 24h, 30m или 90s, а не «" + raw.trim() + "»");
        }
        Duration total = Duration.ZERO;
        Matcher matcher = PART.matcher(text);
        while (matcher.find()) total = total.plus(unit(matcher.group(2).charAt(0), Long.parseLong(matcher.group(1))));
        return total;
    }

    private static Duration unit(char symbol, long amount) {
        return switch (symbol) {
            case 'd' -> Duration.ofDays(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'm' -> Duration.ofMinutes(amount);
            default -> Duration.ofSeconds(amount);
        };
    }

    /** Самая крупная точная единица: 259200 с → «3d», 5400 с → «90m». */
    public static String token(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds > 0 && seconds % DAY == 0) return seconds / DAY + "d";
        if (seconds > 0 && seconds % HOUR == 0) return seconds / HOUR + "h";
        if (seconds > 0 && seconds % MINUTE == 0) return seconds / MINUTE + "m";
        return seconds + "s";
    }
}
