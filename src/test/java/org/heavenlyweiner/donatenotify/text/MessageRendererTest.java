package org.heavenlyweiner.donatenotify.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.heavenlyweiner.donatenotify.config.ConfigException;
import org.heavenlyweiner.donatenotify.config.SettingsLoader;
import org.heavenlyweiner.donatenotify.logic.RemainingStatus;
import org.heavenlyweiner.donatenotify.support.Snapshots;
import org.junit.jupiter.api.Test;

class MessageRendererTest {

    private static final Instant NOW = Snapshots.END.minus(Duration.ofHours(23).plusMinutes(59).plusSeconds(30));

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void default_warning_without_shop_hides_button_line() {
        MessageRenderer renderer = new MessageRenderer(SettingsLoader.defaults());
        String text = plain(renderer.warning("Steve", Snapshots.END, NOW, RemainingStatus.PREMIUM));
        String[] lines = text.split("\n");
        assertEquals(3, lines.length, text);
        assertEquals("Срок вашего Sponsor истечёт через 23 часа 59 минут.", lines[0]);
        assertEquals("Окончание: 15 ноября в 18:30 МСК.", lines[1]);
        assertEquals("После окончания у вас останется Premium.", lines[2]);
        assertFalse(text.contains("Продлить"));
    }

    @Test
    void shop_url_adds_clickable_button() throws ConfigException {
        MessageRenderer renderer = new MessageRenderer(SettingsLoader.parse("shop:\n  url: https://shop.example.com/sponsor\n"));
        Component message = renderer.warning("Steve", Snapshots.END, NOW, RemainingStatus.ORDINARY);
        String text = plain(message);
        assertEquals(4, text.split("\n").length, text);
        assertTrue(text.endsWith("[Продлить Sponsor]"), text);
        ClickEvent<?> click = findClick(message);
        assertNotNull(click);
        assertEquals(ClickEvent.Action.OPEN_URL, click.action());
        assertEquals(ClickEvent.openUrl("https://shop.example.com/sponsor"), click);
    }

    @Test
    void other_year_adds_year_to_date() {
        MessageRenderer renderer = new MessageRenderer(SettingsLoader.defaults());
        Instant end = Instant.parse("2027-01-03T09:00:00Z");
        assertEquals("3 января 2027 в 12:00 МСК", renderer.formatDate(end, NOW));
        assertEquals("15 ноября в 18:30 МСК", renderer.formatDate(Snapshots.END, NOW));
    }

    @Test
    void expired_message_uses_after_end_texts() {
        MessageRenderer renderer = new MessageRenderer(SettingsLoader.defaults());
        String text = plain(renderer.expired("Steve", Snapshots.END, Snapshots.END.plus(Duration.ofDays(1)), RemainingStatus.PREMIUM));
        assertTrue(text.startsWith("Срок вашего Sponsor истёк."), text);
        assertTrue(text.contains("У вас остаётся Premium."), text);
        String neutral = plain(renderer.expired("Steve", Snapshots.END, Snapshots.END, RemainingStatus.NEUTRAL));
        assertTrue(neutral.contains("Возможности Sponsor больше недоступны."), neutral);
    }

    @Test
    void all_placeholders_are_substituted() throws ConfigException {
        String yaml = "shop:\n  url: https://shop.example.com\n"
                + "messages:\n  privilege-name: VIP\n"
                + "  warning: \"<player>|<privilege>|<remaining>|<end_date>|<remaining_status>|<shop_url>|<renew_button>\"\n"
                + "  remaining:\n    neutral: \"<bold>нейтрально</bold>\"\n"
                + "  renew-button:\n    text: \"[кнопка <privilege>]\"\n";
        MessageRenderer renderer = new MessageRenderer(SettingsLoader.parse(yaml));
        String text = plain(renderer.warning("Alex", Snapshots.END, NOW, RemainingStatus.NEUTRAL));
        assertEquals("Alex|VIP|23 часа 59 минут|15 ноября в 18:30 МСК|нейтрально|https://shop.example.com|[кнопка VIP]", text);
    }

    @Test
    void zone_is_configurable() throws ConfigException {
        MessageRenderer renderer = new MessageRenderer(SettingsLoader.parse("time:\n  zone: UTC\n  zone-label: UTC\n"));
        assertEquals("15 ноября в 15:30 UTC", renderer.formatDate(Snapshots.END, NOW));
    }

    private static ClickEvent<?> findClick(Component root) {
        Deque<Component> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Component current = queue.poll();
            if (current.clickEvent() != null) return current.clickEvent();
            queue.addAll(current.children());
        }
        return null;
    }
}
