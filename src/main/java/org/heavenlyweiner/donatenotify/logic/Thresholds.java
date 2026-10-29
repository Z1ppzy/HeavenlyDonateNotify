package org.heavenlyweiner.donatenotify.logic;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Настроенные пороги, от большего к меньшему. */
public final class Thresholds {

    private final List<Threshold> descending;

    private Thresholds(List<Threshold> descending) {
        this.descending = descending;
    }

    /** Из записей конфига: «3d», «24h», «1h». */
    public static Thresholds parse(Collection<String> tokens) {
        List<Threshold> thresholds = new ArrayList<>();
        for (String token : tokens) thresholds.add(Threshold.parse(token));
        return build(thresholds);
    }

    public static Thresholds of(Collection<Duration> durations) {
        List<Threshold> thresholds = new ArrayList<>();
        for (Duration duration : durations) thresholds.add(Threshold.of(duration));
        return build(thresholds);
    }

    private static Thresholds build(List<Threshold> thresholds) {
        if (thresholds.isEmpty()) throw new IllegalArgumentException("нужен хотя бы один порог");
        TreeSet<Threshold> set = new TreeSet<>();
        for (Threshold threshold : thresholds) {
            if (threshold.isExpiry()) throw new IllegalArgumentException("порог должен быть больше нуля");
            if (!set.add(threshold)) throw new IllegalArgumentException("порог " + threshold.token() + " повторяется");
        }
        return new Thresholds(List.copyOf(set.descendingSet()));
    }

    public List<Threshold> list() {
        return descending;
    }

    /**
     * Самый актуальный порог: наименьший из уже пройденных ({@code remaining <= T}).
     * Пусто, если до ближайшего порога ещё далеко или срок уже наступил.
     */
    public Optional<Threshold> due(Duration remaining) {
        if (remaining.isZero() || remaining.isNegative()) return Optional.empty();
        Threshold best = null;
        for (Threshold threshold : descending) {
            if (remaining.compareTo(threshold.duration()) <= 0) best = threshold;
        }
        return Optional.ofNullable(best);
    }

    /** Порог по записи из команды: «24h», «1d» (та же длительность) или «expired». */
    public Optional<Threshold> find(String token) {
        String text = token.trim().toLowerCase(Locale.ROOT);
        if (text.equals(Threshold.EXPIRED_TOKEN) || text.equals("истечение")) return Optional.of(Threshold.EXPIRED);
        Threshold wanted;
        try {
            wanted = Threshold.parse(text);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return descending.stream().filter(wanted::equals).findFirst();
    }

    /** Подпись порога в единицах конфига; для чужого порога (из истории) — его собственная. */
    public String label(Threshold threshold) {
        return descending.stream().filter(threshold::equals).findFirst().orElse(threshold).label();
    }

    /** «3d, 24h, 1h». */
    public String describe() {
        return descending.stream().map(Threshold::token).collect(Collectors.joining(", "));
    }
}
