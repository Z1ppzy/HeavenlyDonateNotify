package org.heavenlyweiner.donatenotify.command;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.heavenlyweiner.donatenotify.config.ActiveSettings;
import org.heavenlyweiner.donatenotify.logic.RemainingStatus;
import org.heavenlyweiner.donatenotify.logic.SponsorAccess;
import org.heavenlyweiner.donatenotify.logic.Threshold;

/** Предпросмотр сообщения для администратора: игроку ничего не отправляется, история не меняется. */
public final class PreviewBuilder {

    /** Реальное предупреждение уходит чуть позже порога, поэтому и предпросмотр показывает «порог минус минута». */
    private static final Duration AFTER_THRESHOLD = Duration.ofMinutes(1);

    private PreviewBuilder() {}

    public static List<Component> build(String name, SponsorAccess access, Threshold threshold, ActiveSettings.Bundle bundle,
                                        Instant now) {
        boolean realTerm = access != null && access.temporary();
        RemainingStatus status = access != null && access.remaining() != null ? access.remaining() : RemainingStatus.NEUTRAL;
        Instant end;
        Instant at;
        if (threshold.isExpiry()) {
            end = realTerm ? access.end() : now;
            at = now.isAfter(end) ? now : end;
        } else {
            end = realTerm ? access.end() : now.plus(threshold.duration());
            at = end.minus(threshold.duration()).plus(AFTER_THRESHOLD);
        }
        String term = realTerm
                ? "срок из LuckPerms: " + bundle.renderer().formatDate(end, now)
                : "у игрока нет временного Sponsor, срок условный";
        Component header = Component.text("Предпросмотр для " + name + " · порог «" + threshold.label() + "» · " + term
                + " · статус после окончания: " + status.name().toLowerCase(java.util.Locale.ROOT), DonateNotifyCommand.BRAND);
        Component message = threshold.isExpiry()
                ? bundle.renderer().expired(name, end, at, status)
                : bundle.renderer().warning(name, end, at, status);
        return List.of(header, message);
    }
}
