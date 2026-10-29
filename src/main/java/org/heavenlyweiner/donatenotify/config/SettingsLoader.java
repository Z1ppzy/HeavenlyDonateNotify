package org.heavenlyweiner.donatenotify.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.heavenlyweiner.donatenotify.logic.Thresholds;
import org.heavenlyweiner.donatenotify.text.DurationParser;
import org.heavenlyweiner.donatenotify.text.MessageRenderer;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** Чтение и проверка config.yml. Любая ошибка — {@link ConfigException} с именем ключа. */
public final class SettingsLoader {

    public static final String FILE_NAME = "config.yml";

    private static final Pattern GROUP_NAME = Pattern.compile("[a-z0-9_.-]{1,36}");
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");
    private static final Duration MIN_INTERVAL = Duration.ofSeconds(10);
    private static final Duration MAX_INTERVAL = Duration.ofHours(1);
    private static final Duration MAX_LOGIN_DELAY = Duration.ofMinutes(10);
    private static final Duration MIN_RETENTION = Duration.ofDays(1);
    private static final Duration MAX_RETENTION = Duration.ofDays(3650);
    private static final Duration MAX_THRESHOLD = Duration.ofDays(365);
    private static final int MAX_THRESHOLDS = 20;
    private static final int MAX_ZONE_LABEL = 16;
    private static final int MAX_TEMPLATE_LENGTH = 4000;

    private SettingsLoader() {}

    /** Читает config.yml из каталога плагина; при первом запуске копирует встроенный образец. */
    public static Settings load(Path dataDir) throws ConfigException, IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(FILE_NAME);
        if (!Files.exists(file)) Files.write(file, bundledConfig());
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Встроенные значения по умолчанию. */
    public static Settings defaults() {
        try {
            return parse(new String(bundledConfig(), StandardCharsets.UTF_8));
        } catch (IOException | ConfigException e) {
            throw new IllegalStateException("встроенный config.yml некорректен", e);
        }
    }

    public static Settings parse(String yaml) throws ConfigException {
        Map<?, ?> defaults;
        try {
            defaults = tree(new String(bundledConfig(), StandardCharsets.UTF_8), "встроенный config.yml");
        } catch (IOException e) {
            throw new ConfigException(FILE_NAME, "встроенный образец недоступен: " + e.getMessage());
        }
        return build(new ConfigNode(tree(yaml, "yaml"), defaults));
    }

    private static byte[] bundledConfig() throws IOException {
        try (InputStream in = SettingsLoader.class.getResourceAsStream("/" + FILE_NAME)) {
            if (in == null) throw new IOException("встроенный " + FILE_NAME + " отсутствует в JAR");
            return in.readAllBytes();
        }
    }

    private static Map<?, ?> tree(String yaml, String path) throws ConfigException {
        Object tree;
        try {
            tree = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (YAMLException e) {
            throw new ConfigException(path, "синтаксическая ошибка: " + firstLine(e.getMessage()));
        }
        if (tree == null) return Map.of();
        if (!(tree instanceof Map<?, ?> map)) throw new ConfigException(path, "корень файла должен быть набором ключей");
        return map;
    }

    static Settings build(ConfigNode node) throws ConfigException {
        String sponsor = groupName(node, "groups.sponsor");
        String premium = groupName(node, "groups.premium");
        if (sponsor.equals(premium)) throw new ConfigException("groups.premium", "должна отличаться от groups.sponsor");
        Set<String> ordinary = new LinkedHashSet<>();
        for (String group : node.stringList("groups.ordinary")) ordinary.add(checkGroupName("groups.ordinary", group.trim()));
        Thresholds thresholds = thresholds(node);
        Duration loginDelay = range(node.duration("checks.login-delay"), Duration.ZERO, MAX_LOGIN_DELAY, "checks.login-delay");
        Duration interval = range(node.duration("checks.interval"), MIN_INTERVAL, MAX_INTERVAL, "checks.interval");
        boolean requireLimboAuth = node.bool("checks.require-limboauth");
        Duration retention = range(node.duration("history.retention"), MIN_RETENTION, MAX_RETENTION, "history.retention");
        Settings.TimeFormat time = time(node);
        String shopUrl = shopUrl(node.string("shop.url").trim());
        Settings.Messages messages = messages(node);
        return new Settings(sponsor, premium, Set.copyOf(ordinary), thresholds, loginDelay, interval, requireLimboAuth,
                retention, time, shopUrl, messages);
    }

    private static String groupName(ConfigNode node, String path) throws ConfigException {
        return checkGroupName(path, node.string(path).trim());
    }

    private static String checkGroupName(String path, String name) throws ConfigException {
        if (!GROUP_NAME.matcher(name).matches()) throw new ConfigException(path, "недопустимое имя группы «" + name + "»");
        return name;
    }

    private static Thresholds thresholds(ConfigNode node) throws ConfigException {
        List<String> tokens = node.stringList("thresholds");
        if (tokens.isEmpty()) throw new ConfigException("thresholds", "нужен хотя бы один порог, например 3d");
        if (tokens.size() > MAX_THRESHOLDS) throw new ConfigException("thresholds", "не больше " + MAX_THRESHOLDS + " порогов");
        for (String token : tokens) {
            Duration duration;
            try {
                duration = DurationParser.parse(token);
            } catch (IllegalArgumentException e) {
                throw new ConfigException("thresholds", e.getMessage());
            }
            if (duration.isZero() || duration.isNegative() || duration.compareTo(MAX_THRESHOLD) > 0) {
                throw new ConfigException("thresholds", "порог " + token + " вне диапазона 1s..365d");
            }
        }
        try {
            return Thresholds.parse(tokens);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("thresholds", e.getMessage());
        }
    }

    private static Duration range(Duration value, Duration min, Duration max, String path) throws ConfigException {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new ConfigException(path, "допустимо от " + DurationParser.token(min) + " до " + DurationParser.token(max));
        }
        return value;
    }

    private static Settings.TimeFormat time(ConfigNode node) throws ConfigException {
        String zoneId = node.string("time.zone").trim();
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneId);
        } catch (DateTimeException e) {
            throw new ConfigException("time.zone", "неизвестный часовой пояс «" + zoneId + "»");
        }
        String label = node.string("time.zone-label").trim();
        if (label.length() > MAX_ZONE_LABEL) throw new ConfigException("time.zone-label", "не длиннее " + MAX_ZONE_LABEL + " символов");
        DateTimeFormatter sameYear = formatter(node.string("time.date-format"), zone, "time.date-format");
        DateTimeFormatter otherYear = formatter(node.string("time.date-format-other-year"), zone, "time.date-format-other-year");
        return new Settings.TimeFormat(zone, label, sameYear, otherYear);
    }

    private static DateTimeFormatter formatter(String pattern, ZoneId zone, String path) throws ConfigException {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern, RUSSIAN).withZone(zone);
            formatter.format(Instant.EPOCH);
            return formatter;
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new ConfigException(path, "неверный формат даты: " + firstLine(e.getMessage()));
        }
    }

    private static String shopUrl(String url) throws ConfigException {
        if (url.isEmpty()) return "";
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ConfigException("shop.url", "некорректный адрес");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new ConfigException("shop.url", "нужен адрес вида https://shop.example.com");
        }
        return url;
    }

    private static Settings.Messages messages(ConfigNode node) throws ConfigException {
        String privilege = node.string("messages.privilege-name").trim();
        if (privilege.isEmpty()) throw new ConfigException("messages.privilege-name", "название не задано");
        Settings.Remaining before = new Settings.Remaining(template(node, "messages.remaining.premium"),
                template(node, "messages.remaining.ordinary"), template(node, "messages.remaining.neutral"));
        Settings.Remaining after = new Settings.Remaining(template(node, "messages.remaining-expired.premium"),
                template(node, "messages.remaining-expired.ordinary"), template(node, "messages.remaining-expired.neutral"));
        String hover = node.string("messages.renew-button.hover");
        if (!hover.isBlank()) checkSyntax("messages.renew-button.hover", hover);
        return new Settings.Messages(privilege, template(node, "messages.warning"), template(node, "messages.expired"),
                before, after, template(node, "messages.renew-button.text"), hover);
    }

    private static String template(ConfigNode node, String path) throws ConfigException {
        String text = node.string(path);
        if (text.isBlank()) throw new ConfigException(path, "текст не задан");
        if (text.length() > MAX_TEMPLATE_LENGTH) throw new ConfigException(path, "текст длиннее " + MAX_TEMPLATE_LENGTH + " символов");
        checkSyntax(path, text);
        return text;
    }

    private static void checkSyntax(String path, String template) throws ConfigException {
        try {
            MessageRenderer.checkSyntax(template);
        } catch (RuntimeException e) {
            throw new ConfigException(path, "ошибка MiniMessage: " + firstLine(e.getMessage()));
        }
    }

    private static String firstLine(String message) {
        if (message == null) return "неизвестная ошибка";
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
