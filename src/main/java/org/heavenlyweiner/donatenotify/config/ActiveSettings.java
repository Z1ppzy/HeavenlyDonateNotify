package org.heavenlyweiner.donatenotify.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.heavenlyweiner.donatenotify.logic.SponsorEvaluator;
import org.heavenlyweiner.donatenotify.logic.Thresholds;
import org.heavenlyweiner.donatenotify.text.MessageRenderer;

/** Действующая конфигурация и производные от неё объекты; при неудачной перезагрузке остаётся прежняя. */
public final class ActiveSettings {

    /** Конфигурация вместе с оценщиком и рендерером, собранными под неё. */
    public record Bundle(Settings settings, SponsorEvaluator evaluator, MessageRenderer renderer) {

        public static Bundle of(Settings settings) {
            return new Bundle(settings,
                    new SponsorEvaluator(settings.sponsorGroup(), settings.premiumGroup(), settings.ordinaryGroups()),
                    new MessageRenderer(settings));
        }

        public Thresholds thresholds() {
            return settings.thresholds();
        }
    }

    private volatile Bundle current;
    private volatile String lastError;

    public ActiveSettings(Settings initial) {
        this.current = Bundle.of(initial);
    }

    public Bundle current() {
        return current;
    }

    public Settings settings() {
        return current.settings();
    }

    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }

    public void markError(String error) {
        this.lastError = error;
    }

    /** Перечитывает конфиг. При ошибке прежняя конфигурация остаётся, возвращается описание ошибки. */
    public Optional<String> reload(Path dataDir) {
        try {
            current = Bundle.of(SettingsLoader.load(dataDir));
            lastError = null;
            return Optional.empty();
        } catch (ConfigException | IOException | RuntimeException e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return Optional.of(lastError);
        }
    }
}
