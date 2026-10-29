package org.heavenlyweiner.donatenotify.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Снимок данных игрока из LuckPerms, достаточный для оценки Sponsor без обращения к API.
 *
 * @param assignments   собственные назначения групп игрока (без наследуемых)
 * @param groupParents  для каждой известной группы — все группы, которые она наследует (с транзитивностью)
 * @param staticContext статический контекст прокси LuckPerms, например {@code server=proxy}
 */
public record UserSnapshot(UUID uuid, String name, List<Assignment> assignments,
                           Map<String, Set<String>> groupParents, Map<String, Set<String>> staticContext) {

    public UserSnapshot {
        assignments = List.copyOf(assignments);
        groupParents = copy(groupParents);
        staticContext = copy(staticContext);
    }

    private static Map<String, Set<String>> copy(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        if (source != null) source.forEach((key, values) -> copy.put(key, Set.copyOf(values)));
        return Map.copyOf(copy);
    }

    public boolean knowsGroup(String group) {
        return groupParents.containsKey(group);
    }

    public Set<String> inheritedBy(String group) {
        return groupParents.getOrDefault(group, Set.of());
    }

    /**
     * Назначение действует на прокси: по каждому ключу контекста узла статический контекст содержит
     * хотя бы одно из его значений (как {@code ContextSet#isSatisfiedBy}). Глобальный узел действует всегда.
     */
    public boolean appliesOnProxy(Assignment assignment) {
        for (Map.Entry<String, Set<String>> entry : assignment.contexts().entrySet()) {
            Set<String> have = staticContext.getOrDefault(entry.getKey(), Set.of());
            if (entry.getValue().stream().noneMatch(have::contains)) return false;
        }
        return true;
    }
}
