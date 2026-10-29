package org.heavenlyweiner.donatenotify.text;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.heavenlyweiner.donatenotify.config.Settings;
import org.heavenlyweiner.donatenotify.logic.RemainingStatus;

/** Сообщения игроку из шаблонов MiniMessage конфига. */
public final class MessageRenderer {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String EXPIRED_REMAINING = "истёк";

    private final Settings settings;

    public MessageRenderer(Settings settings) {
        this.settings = settings;
    }

    /** Предупреждение до окончания. */
    public Component warning(String player, Instant end, Instant now, RemainingStatus status) {
        String remaining = RemainingTime.format(Duration.between(now, end));
        String statusText = settings.messages().remainingText(status, false);
        return render(settings.messages().warning(), resolver(player, remaining, formatDate(end, now), statusText));
    }

    /** Сообщение об уже наступившем окончании. */
    public Component expired(String player, Instant end, Instant now, RemainingStatus status) {
        String statusText = settings.messages().remainingText(status, true);
        return render(settings.messages().expired(), resolver(player, EXPIRED_REMAINING, formatDate(end, now), statusText));
    }

    /** «15 ноября в 18:30 МСК»; год добавляется, если окончание в другом году. */
    public String formatDate(Instant end, Instant now) {
        Settings.TimeFormat time = settings.time();
        int endYear = end.atZone(time.zone()).getYear();
        int nowYear = now.atZone(time.zone()).getYear();
        String text = (endYear == nowYear ? time.sameYear() : time.otherYear()).format(end);
        return time.zoneLabel().isBlank() ? text : text + " " + time.zoneLabel();
    }

    /** Кнопка «Продлить»; пустой компонент, пока адрес магазина не задан. */
    public Component renewButton() {
        if (!settings.shopConfigured()) return Component.empty();
        TagResolver base = TagResolver.resolver(
                Placeholder.unparsed("privilege", settings.messages().privilege()),
                Placeholder.unparsed("shop_url", settings.shopUrl()));
        Component button = MINI.deserialize(settings.messages().buttonText(), base)
                .clickEvent(ClickEvent.openUrl(settings.shopUrl()));
        String hover = settings.messages().buttonHover();
        return hover.isBlank() ? button : button.hoverEvent(HoverEvent.showText(MINI.deserialize(hover, base)));
    }

    private TagResolver resolver(String player, String remaining, String endDate, String statusText) {
        return TagResolver.resolver(
                Placeholder.unparsed("player", player),
                Placeholder.unparsed("privilege", settings.messages().privilege()),
                Placeholder.unparsed("remaining", remaining),
                Placeholder.unparsed("end_date", endDate),
                Placeholder.parsed("remaining_status", statusText),
                Placeholder.unparsed("shop_url", settings.shopUrl()),
                Placeholder.component("renew_button", renewButton()));
    }

    /** Каждая строка шаблона разбирается отдельно; строки, ставшие пустыми (скрытая кнопка), не выводятся. */
    static Component render(String template, TagResolver resolver) {
        List<Component> lines = new ArrayList<>();
        for (String line : template.split("\\R")) {
            Component component = MINI.deserialize(line, resolver);
            if (!isBlank(component)) lines.add(component);
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    static boolean isBlank(Component component) {
        if (!(component instanceof TextComponent text)) return false;
        if (!text.content().isBlank()) return false;
        return component.children().stream().allMatch(MessageRenderer::isBlank);
    }

    /**
     * Проверка шаблона при загрузке конфига: плейсхолдеры подставляются заглушками, а тег, оставшийся
     * в тексте как есть (опечатка или неверный аргумент вроде {@code <color:notacolor>}), считается ошибкой.
     */
    public static void checkSyntax(String template) {
        TagResolver stub = TagResolver.resolver(
                Placeholder.unparsed("player", "Player"), Placeholder.unparsed("privilege", "Sponsor"),
                Placeholder.unparsed("remaining", "1 час"), Placeholder.unparsed("end_date", "сегодня"),
                Placeholder.parsed("remaining_status", "-"), Placeholder.unparsed("shop_url", "https://example.com"),
                Placeholder.component("renew_button", Component.empty()));
        Matcher leftover = UNRESOLVED_TAG.matcher(plainText(render(template, stub)));
        if (leftover.find()) throw new IllegalArgumentException("неизвестный тег " + leftover.group());
    }

    private static final Pattern UNRESOLVED_TAG = Pattern.compile("</?[A-Za-z#][^<>\\s]*>");

    /** Текст без форматирования; переводимые компоненты и прочее не содержат тегов и пропускаются. */
    static String plainText(Component component) {
        StringBuilder out = new StringBuilder();
        if (component instanceof TextComponent text) out.append(text.content());
        for (Component child : component.children()) out.append(plainText(child));
        return out.toString();
    }
}
