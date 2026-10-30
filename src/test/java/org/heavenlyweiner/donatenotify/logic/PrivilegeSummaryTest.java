package org.heavenlyweiner.donatenotify.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrivilegeSummaryTest {

    private static final Instant END = Instant.ofEpochSecond(1_900_000_000L);

    @Test
    void temporary_sponsor_carries_group_and_expiry() {
        PrivilegeSummary summary = PrivilegeSummary.of(
                SponsorAccess.temporary(END, RemainingStatus.ORDINARY, List.of()), "sponsor", "premium");
        assertEquals(AccessKind.TEMPORARY, summary.kind());
        assertEquals("sponsor", summary.group());
        assertEquals(END.getEpochSecond(), summary.expiryEpochSecond());
        assertEquals(Map.of("kind", "TEMPORARY", "group", "sponsor", "expiry", END.getEpochSecond()), summary.toMap());
    }

    @Test
    void permanent_sponsor_and_remaining_premium_are_permanent() {
        PrivilegeSummary sponsor = PrivilegeSummary.of(SponsorAccess.permanent(List.of()), "sponsor", "premium");
        assertEquals(AccessKind.PERMANENT, sponsor.kind());
        assertEquals("sponsor", sponsor.group());
        assertNull(sponsor.expiryEpochSecond());
        PrivilegeSummary premium = PrivilegeSummary.of(
                SponsorAccess.none(RemainingStatus.PREMIUM, List.of()), "sponsor", "premium");
        assertEquals(Map.of("kind", "PERMANENT", "group", "premium"), premium.toMap());
    }

    @Test
    void nothing_and_unknown_have_no_group() {
        assertEquals(Map.of("kind", "NONE", "group", ""),
                PrivilegeSummary.of(SponsorAccess.none(RemainingStatus.ORDINARY, List.of()), "sponsor", "premium").toMap());
        assertEquals(Map.of("kind", "UNKNOWN", "group", ""),
                PrivilegeSummary.of(SponsorAccess.unknown(List.of("заметка")), "sponsor", "premium").toMap());
        assertThrows(IllegalArgumentException.class, () -> new PrivilegeSummary(AccessKind.NONE, "", 5L));
        assertThrows(IllegalArgumentException.class, () -> new PrivilegeSummary(AccessKind.TEMPORARY, "sponsor", null));
    }
}
