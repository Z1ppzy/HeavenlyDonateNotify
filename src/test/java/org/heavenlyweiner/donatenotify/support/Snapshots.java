package org.heavenlyweiner.donatenotify.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.heavenlyweiner.donatenotify.model.Assignment;
import org.heavenlyweiner.donatenotify.model.UserSnapshot;

/** Снимки игрока для тестов: граф групп сети и статический контекст прокси. */
public final class Snapshots {

    public static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final String NAME = "Steve";
    /** Окончание 15 ноября 2026 в 18:30 по Москве. */
    public static final Instant END = Instant.parse("2026-11-15T15:30:00Z");

    public static final Map<String, Set<String>> PARENTS = Map.of(
            "default", Set.of(),
            "premium", Set.of(),
            "sponsor", Set.of("premium"),
            "vip", Set.of("sponsor", "premium"),
            "elite", Set.of("premium"),
            "admin", Set.of());

    public static final Map<String, Set<String>> STATIC_CONTEXT = Map.of("server", Set.of("proxy"));

    private Snapshots() {}

    public static UserSnapshot of(Assignment... assignments) {
        return new UserSnapshot(PLAYER, NAME, List.of(assignments), PARENTS, STATIC_CONTEXT);
    }

    public static UserSnapshot withParents(Map<String, Set<String>> parents, Assignment... assignments) {
        return new UserSnapshot(PLAYER, NAME, List.of(assignments), parents, STATIC_CONTEXT);
    }

    public static Assignment defaultGroup() {
        return Assignment.permanent("default");
    }

    public static Assignment sponsorUntil(Instant end) {
        return Assignment.temporary("sponsor", end);
    }

    public static Assignment contextual(String group, Instant expiry, String server) {
        return new Assignment(group, expiry, Map.of("server", Set.of(server)));
    }
}
