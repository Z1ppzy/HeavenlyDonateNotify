package org.heavenlyweiner.donatenotify.command;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.heavenlyweiner.donatenotify.config.ActiveSettings;
import org.heavenlyweiner.donatenotify.config.Settings;
import org.heavenlyweiner.donatenotify.logic.AccessKind;
import org.heavenlyweiner.donatenotify.logic.RemainingStatus;
import org.heavenlyweiner.donatenotify.logic.SponsorAccess;
import org.heavenlyweiner.donatenotify.logic.Threshold;
import org.heavenlyweiner.donatenotify.login.LoginGate;
import org.heavenlyweiner.donatenotify.luckperms.LuckPermsSource;
import org.heavenlyweiner.donatenotify.model.Assignment;
import org.heavenlyweiner.donatenotify.storage.SentRecord;
import org.heavenlyweiner.donatenotify.storage.TrackedState;
import org.heavenlyweiner.donatenotify.storage.TrackedSubscription;
import org.heavenlyweiner.donatenotify.text.DurationParser;
import org.heavenlyweiner.donatenotify.text.RemainingTime;

/** Текст {@code /donatenotify status}: назначения, срок, остающийся статус, история и состояние плагина. */
public final class StatusReport {

    /** Всё, что собрано для отчёта; {@code uuid == null} — игрок не найден. */
    public record Input(String name, UUID uuid, boolean online, String server, LoginGate.Verdict verdict,
                        LuckPermsSource.Result data, SponsorAccess access, List<TrackedSubscription> tracked,
                        List<SentRecord> sent, String storageError, String configError, boolean luckPermsAvailable,
                        ActiveSettings.Bundle bundle, Instant now) {}

    private static final TextColor BRAND = DonateNotifyCommand.BRAND;
    private static final TextColor NORMAL = DonateNotifyCommand.NORMAL;
    private static final TextColor ALERT = DonateNotifyCommand.ALERT;
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private StatusReport() {}

    public static List<Component> render(Input in) {
        List<Component> out = new ArrayList<>();
        if (in.uuid() == null) {
            out.add(Component.text("HeavenlyDonateNotify · " + in.name(), BRAND)
                    .append(Component.text(" — игрок не онлайн и не найден в LuckPerms"
                            + (in.luckPermsAvailable() ? "" : " (LuckPerms недоступен)"), ALERT)));
            out.add(plugin(in));
            return out;
        }
        out.add(Component.text("HeavenlyDonateNotify · " + in.name(), BRAND).append(Component.text(" (" + in.uuid() + ")", NORMAL)));
        out.add(login(in));
        out.addAll(data(in));
        out.addAll(history(in));
        out.add(plugin(in));
        return out;
    }

    private static Component login(Input in) {
        if (!in.online()) return kv("Вход", "офлайн");
        String server = in.server() == null ? "не на игровом сервере" : "сервер " + in.server();
        String verdict = switch (in.verdict()) {
            case ALLOW -> "вход подтверждён, уведомления разрешены";
            case NOT_ON_BACKEND -> "лимбо авторизации — уведомления не отправляются";
            case AUTH_UNAVAILABLE -> "LimboAuth не готов или отсутствует — уведомления не отправляются";
        };
        return kv("Вход", "онлайн · " + server + " · " + verdict);
    }

    private static List<Component> data(Input in) {
        List<Component> out = new ArrayList<>();
        if (!in.data().ok()) {
            out.add(Component.text("LuckPerms: ", BRAND).append(Component.text(in.data().error(), ALERT)));
            return out;
        }
        out.add(kv("LuckPerms", "данные получены · статический контекст прокси: " + contexts(in.data().snapshot().staticContext())));
        out.add(Component.text("Назначения:", BRAND));
        for (Assignment assignment : in.data().snapshot().assignments()) {
            String term = assignment.permanent() ? "бессрочно" : "до " + in.bundle().renderer().formatDate(assignment.expiry(), in.now());
            boolean applies = in.data().snapshot().appliesOnProxy(assignment);
            out.add(Component.text("  • " + assignment.group() + " — " + term + " · " + assignment.contextText()
                    + (applies ? "" : " · не действует на прокси"), applies ? NORMAL : ALERT));
        }
        if (in.data().snapshot().assignments().isEmpty()) out.add(Component.text("  (нет)", NORMAL));
        out.add(kv("Sponsor", sponsor(in)));
        if (in.access().remaining() != null) out.add(kv("После окончания", remaining(in.access().remaining())));
        for (String note : in.access().notes()) out.add(Component.text("  ! " + note, ALERT));
        return out;
    }

    private static String sponsor(Input in) {
        SponsorAccess access = in.access();
        return switch (access.kind()) {
            case NONE -> "нет";
            case PERMANENT -> "бессрочный — уведомления не нужны";
            case UNKNOWN -> "не определён однозначно — плагин ничего не отправляет (см. заметки)";
            case TEMPORARY -> {
                Duration left = Duration.between(in.now(), access.end());
                String remaining = left.isNegative() || left.isZero() ? "срок наступил" : "осталось " + RemainingTime.format(left);
                yield "временный · окончание " + in.bundle().renderer().formatDate(access.end(), in.now()) + " · " + remaining;
            }
        };
    }

    private static String remaining(RemainingStatus status) {
        return switch (status) {
            case PREMIUM -> "останется независимый бессрочный Premium";
            case ORDINARY -> "останутся только группы обычного игрока";
            case NEUTRAL -> "нейтральный текст (персонал, временные группы или неясные данные)";
        };
    }

    private static List<Component> history(Input in) {
        List<Component> out = new ArrayList<>();
        DateTimeFormatter dates = SHORT_DATE.withZone(in.bundle().settings().time().zone());
        out.add(Component.text("Отслеживаемые сроки:", BRAND));
        if (in.tracked().isEmpty()) out.add(Component.text("  (нет)", NORMAL));
        for (TrackedSubscription tracked : in.tracked()) {
            out.add(Component.text("  • до " + dates.format(tracked.expiry()) + " — " + state(tracked.state())
                    + " · впервые " + dates.format(tracked.firstSeen()) + ", последний раз " + dates.format(tracked.lastSeen()), NORMAL));
        }
        out.add(Component.text("Отправленные уведомления:", BRAND));
        if (in.sent().isEmpty()) out.add(Component.text("  (нет)", NORMAL));
        Map<Instant, List<SentRecord>> byExpiry = in.sent().stream()
                .collect(Collectors.groupingBy(SentRecord::expiry, TreeMap::new, Collectors.toList()));
        byExpiry.forEach((expiry, records) -> {
            String list = records.stream()
                    .map(r -> in.bundle().thresholds().label(r.threshold()) + " (" + dates.format(r.sentAt()) + ")")
                    .collect(Collectors.joining(", "));
            boolean current = in.access() != null && in.access().temporary() && in.access().end().equals(expiry);
            out.add(Component.text("  • срок " + dates.format(expiry) + (current ? " (текущий)" : "") + ": " + list, NORMAL));
        });
        if (in.access() != null && in.access().temporary()) out.add(pending(in));
        return out;
    }

    private static Component pending(Input in) {
        Set<Threshold> sent = in.sent().stream().filter(r -> r.expiry().equals(in.access().end()))
                .map(SentRecord::threshold).collect(Collectors.toSet());
        String text = in.bundle().thresholds().list().stream()
                .map(t -> t.label() + (sent.contains(t) ? " ✓" : " —"))
                .collect(Collectors.joining(" · "));
        return kv("Для текущего срока", text + " · истечение" + (sent.contains(Threshold.EXPIRED) ? " ✓" : " —"));
    }

    private static Component plugin(Input in) {
        Settings settings = in.bundle().settings();
        String storage = in.storageError() == null ? "исправно" : "ошибка: " + in.storageError();
        String config = in.configError() == null ? "ок" : "ошибка, действует прежний: " + in.configError();
        String line = "хранилище " + storage + " · конфиг " + config + " · пороги " + settings.thresholds().describe()
                + " · задержка после входа " + DurationParser.token(settings.loginDelay())
                + " · интервал " + DurationParser.token(settings.checkInterval())
                + " · магазин " + (settings.shopConfigured() ? "задан" : "не задан, кнопка скрыта")
                + " · LimboAuth " + (settings.requireLimboAuth() ? "обязателен" : "не требуется");
        return kv("Плагин", line);
    }

    private static String state(TrackedState state) {
        return switch (state) {
            case ACTIVE -> "активен";
            case SUPERSEDED -> "заменён новым сроком";
            case REVOKED -> "снят вручную до срока";
            case EXPIRED_NOTIFIED -> "истёк, сообщение отправлено";
        };
    }

    private static String contexts(Map<String, Set<String>> context) {
        if (context.isEmpty()) return "пусто";
        return new TreeMap<>(context).entrySet().stream()
                .map(e -> e.getKey() + "=" + String.join("|", e.getValue()))
                .collect(Collectors.joining(", "));
    }

    private static Component kv(String key, String value) {
        return Component.text(key + ": ", BRAND).append(Component.text(value, NORMAL));
    }
}
