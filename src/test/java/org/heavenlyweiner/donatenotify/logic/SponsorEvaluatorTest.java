package org.heavenlyweiner.donatenotify.logic;

import static org.heavenlyweiner.donatenotify.support.Snapshots.END;
import static org.heavenlyweiner.donatenotify.support.Snapshots.contextual;
import static org.heavenlyweiner.donatenotify.support.Snapshots.defaultGroup;
import static org.heavenlyweiner.donatenotify.support.Snapshots.of;
import static org.heavenlyweiner.donatenotify.support.Snapshots.sponsorUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.heavenlyweiner.donatenotify.model.Assignment;
import org.heavenlyweiner.donatenotify.support.Snapshots;
import org.junit.jupiter.api.Test;

class SponsorEvaluatorTest {

    private final SponsorEvaluator evaluator = new SponsorEvaluator("sponsor", "premium", Set.of("default"));
    private final Instant now = END.minus(Duration.ofDays(2));

    @Test
    void temporary_global_sponsor_has_end_and_ordinary_remaining() {
        SponsorAccess access = evaluator.evaluate(of(defaultGroup(), sponsorUntil(END)), now);
        assertEquals(AccessKind.TEMPORARY, access.kind());
        assertEquals(END, access.end());
        assertEquals(RemainingStatus.ORDINARY, access.remaining());
        assertTrue(access.notes().isEmpty());
    }

    @Test
    void permanent_sponsor_wins_over_temporary() {
        SponsorAccess access = evaluator.evaluate(of(defaultGroup(), sponsorUntil(END), Assignment.permanent("sponsor")), now);
        assertEquals(AccessKind.PERMANENT, access.kind());
        assertNull(access.end());
    }

    @Test
    void latest_expiry_wins_among_temporary_assignments() {
        Instant later = END.plus(Duration.ofDays(10));
        Assignment vip = Assignment.temporary("vip", later);
        SponsorAccess access = evaluator.evaluate(of(defaultGroup(), sponsorUntil(END), vip), now);
        assertEquals(AccessKind.TEMPORARY, access.kind());
        assertEquals(later, access.end());
    }

    @Test
    void sponsor_inherited_through_permanent_parent_is_permanent() {
        SponsorAccess access = evaluator.evaluate(of(defaultGroup(), sponsorUntil(END), Assignment.permanent("vip")), now);
        assertEquals(AccessKind.PERMANENT, access.kind());
    }

    @Test
    void sponsor_to_premium_inheritance_is_not_permanent_premium() {
        SponsorAccess access = evaluator.evaluate(of(defaultGroup(), sponsorUntil(END)), now);
        assertEquals(RemainingStatus.ORDINARY, access.remaining());
    }

    @Test
    void independent_permanent_premium_remains() {
        SponsorAccess access = evaluator.evaluate(of(defaultGroup(), sponsorUntil(END), Assignment.permanent("premium")), now);
        assertEquals(RemainingStatus.PREMIUM, access.remaining());
    }

    @Test
    void premium_through_other_permanent_group_remains() {
        SponsorAccess access = evaluator.evaluate(of(defaultGroup(), sponsorUntil(END), Assignment.permanent("elite")), now);
        assertEquals(RemainingStatus.PREMIUM, access.remaining());
    }

    @Test
    void temporary_premium_is_not_permanent_premium() {
        Assignment premiumLater = Assignment.temporary("premium", END.plus(Duration.ofDays(5)));
        assertEquals(RemainingStatus.NEUTRAL, evaluator.evaluate(of(defaultGroup(), sponsorUntil(END), premiumLater), now).remaining());
        Assignment premiumEarlier = Assignment.temporary("premium", END.minus(Duration.ofHours(1)));
        assertEquals(RemainingStatus.ORDINARY, evaluator.evaluate(of(defaultGroup(), sponsorUntil(END), premiumEarlier), now).remaining());
    }

    @Test
    void staff_group_gives_neutral_text() {
        SponsorAccess access = evaluator.evaluate(of(defaultGroup(), sponsorUntil(END), Assignment.permanent("admin")), now);
        assertEquals(RemainingStatus.NEUTRAL, access.remaining());
    }

    @Test
    void contextual_temporary_sponsor_is_skipped_with_note() {
        Assignment survival = contextual("sponsor", END.plus(Duration.ofDays(30)), "survival");
        SponsorAccess access = evaluator.evaluate(of(defaultGroup(), sponsorUntil(END), survival), now);
        assertEquals(AccessKind.TEMPORARY, access.kind());
        assertEquals(END, access.end());
        assertEquals(1, access.notes().size());
        assertTrue(access.notes().get(0).contains("server=survival"));
    }

    @Test
    void contextual_permanent_sponsor_makes_access_unknown() {
        SponsorAccess access = evaluator.evaluate(of(defaultGroup(), sponsorUntil(END), contextual("sponsor", null, "survival")), now);
        assertEquals(AccessKind.UNKNOWN, access.kind());
        assertFalse(access.notes().isEmpty());
    }

    @Test
    void only_contextual_sponsor_is_unknown_not_none() {
        SponsorAccess access = evaluator.evaluate(of(defaultGroup(), contextual("sponsor", END, "survival")), now);
        assertEquals(AccessKind.UNKNOWN, access.kind());
    }

    @Test
    void context_satisfied_by_proxy_static_context_applies() {
        SponsorAccess access = evaluator.evaluate(of(defaultGroup(), contextual("sponsor", END, "proxy")), now);
        assertEquals(AccessKind.TEMPORARY, access.kind());
        assertEquals(END, access.end());
    }

    @Test
    void contextual_other_group_makes_remaining_neutral() {
        Assignment premiumOnLobby = contextual("premium", null, "lobby");
        SponsorAccess access = evaluator.evaluate(of(defaultGroup(), sponsorUntil(END), premiumOnLobby), now);
        assertEquals(AccessKind.TEMPORARY, access.kind());
        assertEquals(RemainingStatus.NEUTRAL, access.remaining());
    }

    @Test
    void unknown_group_is_noted_and_remaining_neutral() {
        SponsorAccess access = evaluator.evaluate(of(defaultGroup(), sponsorUntil(END), Assignment.permanent("mystery")), now);
        assertEquals(AccessKind.TEMPORARY, access.kind());
        assertEquals(RemainingStatus.NEUTRAL, access.remaining());
        assertTrue(access.notes().get(0).contains("mystery"));
    }

    @Test
    void expired_assignments_are_ignored() {
        SponsorAccess access = evaluator.evaluate(of(defaultGroup(), sponsorUntil(END)), END.plus(Duration.ofMinutes(1)));
        assertEquals(AccessKind.NONE, access.kind());
        assertEquals(RemainingStatus.ORDINARY, access.remaining());
    }

    @Test
    void no_assignments_is_none() {
        assertEquals(AccessKind.NONE, evaluator.evaluate(of(), now).kind());
    }

    @Test
    void remaining_after_loss_uses_current_groups() {
        SponsorAccess access = evaluator.evaluate(of(defaultGroup(), Assignment.permanent("premium")), now);
        assertEquals(AccessKind.NONE, access.kind());
        assertEquals(RemainingStatus.PREMIUM, access.remaining());
    }

    @Test
    void custom_group_names_are_respected() {
        SponsorEvaluator custom = new SponsorEvaluator("donor", "gold", Set.of("default", "member"));
        Map<String, Set<String>> parents = Map.of("default", Set.of(), "member", Set.of(), "donor", Set.of(), "gold", Set.of());
        SponsorAccess access = custom.evaluate(Snapshots.withParents(parents, defaultGroup(), Assignment.permanent("member"),
                Assignment.temporary("donor", END), Assignment.permanent("gold")), now);
        assertEquals(AccessKind.TEMPORARY, access.kind());
        assertEquals(RemainingStatus.PREMIUM, access.remaining());
    }
}
