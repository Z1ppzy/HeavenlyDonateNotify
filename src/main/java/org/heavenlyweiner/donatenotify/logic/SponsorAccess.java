package org.heavenlyweiner.donatenotify.logic;

import java.time.Instant;
import java.util.List;

/** Итог оценки Sponsor: вид доступа, срок, остающийся статус и заметки о пропущенных назначениях. */
public record SponsorAccess(AccessKind kind, Instant end, RemainingStatus remaining, List<String> notes) {

    public SponsorAccess {
        notes = List.copyOf(notes);
        if (kind == AccessKind.TEMPORARY && end == null) throw new IllegalArgumentException("временный доступ без срока");
    }

    public static SponsorAccess none(RemainingStatus remaining, List<String> notes) {
        return new SponsorAccess(AccessKind.NONE, null, remaining, notes);
    }

    public static SponsorAccess temporary(Instant end, RemainingStatus remaining, List<String> notes) {
        return new SponsorAccess(AccessKind.TEMPORARY, end, remaining, notes);
    }

    public static SponsorAccess permanent(List<String> notes) {
        return new SponsorAccess(AccessKind.PERMANENT, null, null, notes);
    }

    public static SponsorAccess unknown(List<String> notes) {
        return new SponsorAccess(AccessKind.UNKNOWN, null, null, notes);
    }

    public boolean temporary() {
        return kind == AccessKind.TEMPORARY;
    }
}
