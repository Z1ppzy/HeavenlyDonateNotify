package org.heavenlyweiner.donatenotify.logic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Привилегия игрока для других плагинов прокси: Sponsor (временный или бессрочный), иначе
 * независимый бессрочный Premium, иначе ничего. {@code expiryEpochSecond} задан только для временной.
 */
public record PrivilegeSummary(AccessKind kind, String group, Long expiryEpochSecond) {

    public PrivilegeSummary {
        if (kind == null || group == null) throw new IllegalArgumentException("вид и группа привилегии обязательны");
        if ((kind == AccessKind.TEMPORARY) != (expiryEpochSecond != null)) {
            throw new IllegalArgumentException("срок есть только у временной привилегии");
        }
    }

    public static PrivilegeSummary of(SponsorAccess access, String sponsorGroup, String premiumGroup) {
        return switch (access.kind()) {
            case PERMANENT -> new PrivilegeSummary(AccessKind.PERMANENT, sponsorGroup, null);
            case TEMPORARY -> new PrivilegeSummary(AccessKind.TEMPORARY, sponsorGroup, access.end().getEpochSecond());
            case NONE -> access.remaining() == RemainingStatus.PREMIUM
                    ? new PrivilegeSummary(AccessKind.PERMANENT, premiumGroup, null)
                    : new PrivilegeSummary(AccessKind.NONE, "", null);
            case UNKNOWN -> unknown();
        };
    }

    /** Данные LuckPerms недоступны или неоднозначны: ничего не утверждаем. */
    public static PrivilegeSummary unknown() {
        return new PrivilegeSummary(AccessKind.UNKNOWN, "", null);
    }

    /** Плоское представление без типов плагина: {@code kind}, {@code group} и {@code expiry} в секундах epoch. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", kind.name());
        map.put("group", group);
        if (expiryEpochSecond != null) map.put("expiry", expiryEpochSecond);
        return Collections.unmodifiableMap(map);
    }
}
