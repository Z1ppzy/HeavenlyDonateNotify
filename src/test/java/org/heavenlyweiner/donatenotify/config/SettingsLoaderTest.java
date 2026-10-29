package org.heavenlyweiner.donatenotify.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsLoaderTest {

    @TempDir Path directory;

    @Test
    void bundled_defaults_match_specification() {
        Settings settings = SettingsLoader.defaults();
        assertEquals("sponsor", settings.sponsorGroup());
        assertEquals("premium", settings.premiumGroup());
        assertEquals(Set.of("default"), settings.ordinaryGroups());
        assertEquals("3d, 24h, 1h", settings.thresholds().describe());
        assertEquals(Duration.ofSeconds(10), settings.loginDelay());
        assertEquals(Duration.ofSeconds(60), settings.checkInterval());
        assertTrue(settings.requireLimboAuth());
        assertEquals(Duration.ofDays(90), settings.retention());
        assertEquals(ZoneId.of("Europe/Moscow"), settings.time().zone());
        assertEquals("МСК", settings.time().zoneLabel());
        assertFalse(settings.shopConfigured());
        assertEquals("Sponsor", settings.messages().privilege());
    }

    @Test
    void partial_config_falls_back_to_defaults() throws ConfigException {
        Settings settings = SettingsLoader.parse("thresholds: [7d, 12h]\nchecks:\n  login-delay: 0s\nshop:\n  url: https://shop.example.com\n");
        assertEquals("7d, 12h", settings.thresholds().describe());
        assertEquals(Duration.ZERO, settings.loginDelay());
        assertEquals(Duration.ofSeconds(60), settings.checkInterval());
        assertEquals("https://shop.example.com", settings.shopUrl());
        assertEquals("Europe/Moscow", settings.time().zone().getId());
    }

    @Test
    void empty_file_equals_defaults() throws ConfigException {
        assertEquals("3d, 24h, 1h", SettingsLoader.parse("").thresholds().describe());
    }

    @Test
    void invalid_values_name_the_key() {
        assertKeyRejected("thresholds", "thresholds: []\n");
        assertKeyRejected("thresholds", "thresholds: [3d, 72h]\n");
        assertKeyRejected("thresholds", "thresholds: [soon]\n");
        assertKeyRejected("checks.interval", "checks:\n  interval: 5s\n");
        assertKeyRejected("checks.login-delay", "checks:\n  login-delay: 1h\n");
        assertKeyRejected("checks.require-limboauth", "checks:\n  require-limboauth: maybe\n");
        assertKeyRejected("history.retention", "history:\n  retention: 1h\n");
        assertKeyRejected("time.zone", "time:\n  zone: Mars/Olympus\n");
        assertKeyRejected("time.date-format", "time:\n  date-format: \"d MMMM 'в\"\n");
        assertKeyRejected("shop.url", "shop:\n  url: ftp://shop.example.com\n");
        assertKeyRejected("shop.url", "shop:\n  url: shop.example.com\n");
        assertKeyRejected("groups.sponsor", "groups:\n  sponsor: \"Sponsor!\"\n");
        assertKeyRejected("groups.premium", "groups:\n  premium: sponsor\n");
        assertKeyRejected("messages.warning", "messages:\n  warning: \"  \"\n");
        assertKeyRejected("messages.remaining.premium", "messages:\n  remaining:\n    premium: [a, b]\n");
        assertKeyRejected("yaml", "thresholds: [3d\n");
        assertKeyRejected("yaml", "- just a list\n");
    }

    private static void assertKeyRejected(String key, String yaml) {
        ConfigException error = assertThrows(ConfigException.class, () -> SettingsLoader.parse(yaml), yaml);
        assertTrue(error.getMessage().startsWith(key + ":"), error.getMessage());
    }

    @Test
    void load_creates_default_file_and_reload_keeps_last_working_config() throws Exception {
        Settings initial = SettingsLoader.load(directory);
        Path file = directory.resolve(SettingsLoader.FILE_NAME);
        assertTrue(Files.exists(file));
        assertEquals("3d, 24h, 1h", initial.thresholds().describe());

        ActiveSettings active = new ActiveSettings(initial);
        Files.writeString(file, "thresholds: [2d]\n", StandardCharsets.UTF_8);
        assertEquals(Optional.empty(), active.reload(directory));
        assertEquals("2d", active.settings().thresholds().describe());

        Files.writeString(file, "thresholds: [nonsense]\n", StandardCharsets.UTF_8);
        Optional<String> error = active.reload(directory);
        assertTrue(error.isPresent());
        assertEquals("2d", active.settings().thresholds().describe());
        assertEquals(error, active.lastError());

        Files.writeString(file, "thresholds: [5d, 1h]\n", StandardCharsets.UTF_8);
        assertEquals(Optional.empty(), active.reload(directory));
        assertEquals("5d, 1h", active.settings().thresholds().describe());
        assertEquals(Optional.empty(), active.lastError());
    }

    @Test
    void messages_are_validated_as_minimessage() {
        assertKeyRejected("messages.warning", "messages:\n  warning: \"<color:notacolor>x</color>\"\n");
    }
}
