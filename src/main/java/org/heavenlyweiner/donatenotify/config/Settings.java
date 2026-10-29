package org.heavenlyweiner.donatenotify.config;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import org.heavenlyweiner.donatenotify.logic.RemainingStatus;
import org.heavenlyweiner.donatenotify.logic.Thresholds;

/** Проверенная конфигурация плагина. Создаётся только через {@link SettingsLoader}. */
public record Settings(String sponsorGroup, String premiumGroup, Set<String> ordinaryGroups, Thresholds thresholds,
                       Duration loginDelay, Duration checkInterval, boolean requireLimboAuth, Duration retention,
                       TimeFormat time, String shopUrl, Messages messages) {

    public boolean shopConfigured() {
        return !shopUrl.isBlank();
    }

    /** Часовой пояс и форматы даты окончания. */
    public record TimeFormat(ZoneId zone, String zoneLabel, DateTimeFormatter sameYear, DateTimeFormatter otherYear) {}

    /** Тексты MiniMessage. */
    public record Messages(String privilege, String warning, String expired, Remaining beforeEnd, Remaining afterEnd,
                           String buttonText, String buttonHover) {

        public String remainingText(RemainingStatus status, boolean expired) {
            Remaining set = expired ? afterEnd : beforeEnd;
            return switch (status) {
                case PREMIUM -> set.premium();
                case ORDINARY -> set.ordinary();
                case NEUTRAL -> set.neutral();
            };
        }
    }

    /** Фразы о том, что останется после Sponsor. */
    public record Remaining(String premium, String ordinary, String neutral) {}
}
