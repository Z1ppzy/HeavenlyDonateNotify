package org.heavenlyweiner.donatenotify.logic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.heavenlyweiner.donatenotify.model.Assignment;
import org.heavenlyweiner.donatenotify.model.UserSnapshot;

/**
 * Чистая оценка Sponsor по снимку LuckPerms.
 *
 * <p>Доступ к Sponsor дают собственные назначения группы sponsor и групп, которые её наследуют.
 * Бессрочное назначение делает доступ бессрочным; иначе окончание — самый поздний срок.
 * Назначения с контекстами, которые прокси не выполняет, пропускаются с заметкой; если среди них
 * бессрочный Sponsor, итог — UNKNOWN. Остающийся статус считается только по назначениям,
 * не связанным со Sponsor: наследование sponsor → premium бессрочным Premium не считается.
 */
public final class SponsorEvaluator {

    private final String sponsorGroup;
    private final String premiumGroup;
    private final Set<String> ordinaryGroups;

    public SponsorEvaluator(String sponsorGroup, String premiumGroup, Set<String> ordinaryGroups) {
        this.sponsorGroup = sponsorGroup;
        this.premiumGroup = premiumGroup;
        this.ordinaryGroups = Set.copyOf(ordinaryGroups);
    }

    public SponsorAccess evaluate(UserSnapshot user, Instant now) {
        Sorted sorted = sort(user, now);
        if (sorted.permanentSponsorSkipped) return SponsorAccess.unknown(sorted.notes);
        if (sorted.sponsor.isEmpty()) {
            if (sorted.sponsorSkipped > 0) return SponsorAccess.unknown(sorted.notes);
            return SponsorAccess.none(remaining(user, sorted, now), sorted.notes);
        }
        if (sorted.sponsor.stream().anyMatch(Assignment::permanent)) return SponsorAccess.permanent(sorted.notes);
        Instant end = sorted.sponsor.stream().map(Assignment::expiry).max(Comparator.naturalOrder()).orElseThrow();
        return SponsorAccess.temporary(end, remaining(user, sorted, end), sorted.notes);
    }

    private static final class Sorted {
        final List<Assignment> sponsor = new ArrayList<>();
        final List<Assignment> other = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        int sponsorSkipped;
        boolean permanentSponsorSkipped;
        boolean ambiguous;
    }

    private Sorted sort(UserSnapshot user, Instant now) {
        Sorted sorted = new Sorted();
        for (Assignment assignment : user.assignments()) {
            if (assignment.expiredAt(now)) continue;
            Boolean grantsSponsor = grants(user, assignment, sponsorGroup);
            if (grantsSponsor == null) {
                sorted.ambiguous = true;
                sorted.notes.add("группа «" + assignment.group() + "» не загружена в LuckPerms — назначение пропущено");
                continue;
            }
            if (!user.appliesOnProxy(assignment)) {
                skipContextual(sorted, assignment, grantsSponsor);
                continue;
            }
            (grantsSponsor ? sorted.sponsor : sorted.other).add(assignment);
        }
        return sorted;
    }

    private static void skipContextual(Sorted sorted, Assignment assignment, boolean grantsSponsor) {
        String kind = assignment.permanent() ? "бессрочное" : "временное";
        sorted.notes.add(kind + " назначение «" + assignment.group() + "» с контекстом " + assignment.contextText()
                + " не оценивается на прокси — пропущено");
        if (grantsSponsor) {
            sorted.sponsorSkipped++;
            if (assignment.permanent()) sorted.permanentSponsorSkipped = true;
        } else {
            sorted.ambiguous = true;
        }
    }

    /** Даёт ли назначение группу {@code target} напрямую или по наследованию; {@code null} — граф группы неизвестен. */
    private static Boolean grants(UserSnapshot user, Assignment assignment, String target) {
        if (assignment.group().equals(target)) return true;
        if (!user.knowsGroup(assignment.group())) return null;
        return user.inheritedBy(assignment.group()).contains(target);
    }

    private RemainingStatus remaining(UserSnapshot user, Sorted sorted, Instant at) {
        boolean premium = sorted.other.stream()
                .anyMatch(a -> a.permanent() && Boolean.TRUE.equals(grants(user, a, premiumGroup)));
        if (premium) return RemainingStatus.PREMIUM;
        if (sorted.ambiguous) return RemainingStatus.NEUTRAL;
        Set<String> left = new HashSet<>();
        for (Assignment assignment : sorted.other) {
            if (!assignment.activeAt(at)) continue;
            left.add(assignment.group());
            left.addAll(user.inheritedBy(assignment.group()));
        }
        return ordinaryGroups.containsAll(left) ? RemainingStatus.ORDINARY : RemainingStatus.NEUTRAL;
    }
}
