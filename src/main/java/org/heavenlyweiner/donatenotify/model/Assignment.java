package org.heavenlyweiner.donatenotify.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Назначение группы игроку в LuckPerms: группа, срок ({@code null} — бессрочно) и контексты узла. */
public record Assignment(String group, Instant expiry, Map<String, Set<String>> contexts) {

    public Assignment {
        if (group == null || group.isBlank()) throw new IllegalArgumentException("группа назначения не задана");
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        if (contexts != null) contexts.forEach((key, values) -> copy.put(key, Set.copyOf(values)));
        contexts = Map.copyOf(copy);
    }

    public static Assignment permanent(String group) {
        return new Assignment(group, null, Map.of());
    }

    public static Assignment temporary(String group, Instant expiry) {
        return new Assignment(group, expiry, Map.of());
    }

    public boolean permanent() {
        return expiry == null;
    }

    public boolean expiredAt(Instant now) {
        return expiry != null && !expiry.isAfter(now);
    }

    /** Назначение ещё действует в момент {@code at}. */
    public boolean activeAt(Instant at) {
        return expiry == null || expiry.isAfter(at);
    }

    public boolean global() {
        return contexts.isEmpty();
    }

    /** «глобально» или {@code server=lobby, world=nether}. */
    public String contextText() {
        if (contexts.isEmpty()) return "глобально";
        return new TreeMap<>(contexts).entrySet().stream()
                .map(e -> e.getKey() + "=" + String.join("|", new TreeSet<>(e.getValue())))
                .collect(Collectors.joining(", "));
    }
}
