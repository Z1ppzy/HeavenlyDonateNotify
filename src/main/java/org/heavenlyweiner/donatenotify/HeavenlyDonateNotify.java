package org.heavenlyweiner.donatenotify;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.heavenlyweiner.donatenotify.command.AdminActions;
import org.heavenlyweiner.donatenotify.command.DonateNotifyCommand;
import org.heavenlyweiner.donatenotify.config.ActiveSettings;
import org.heavenlyweiner.donatenotify.config.ConfigException;
import org.heavenlyweiner.donatenotify.config.SettingsLoader;
import org.heavenlyweiner.donatenotify.logic.LogThrottle;
import org.heavenlyweiner.donatenotify.logic.NotificationEngine;
import org.heavenlyweiner.donatenotify.login.LimboAuthProbe;
import org.heavenlyweiner.donatenotify.login.LoginGate;
import org.heavenlyweiner.donatenotify.luckperms.LuckPermsSource;
import org.heavenlyweiner.donatenotify.storage.HistoryStore;
import org.heavenlyweiner.donatenotify.storage.SqliteHistoryStore;
import org.heavenlyweiner.donatenotify.storage.StorageException;
import org.slf4j.Logger;

/**
 * Предупреждает игроков об окончании временной привилегии Sponsor по данным LuckPerms на прокси.
 * Устанавливается только на Velocity; группы, права и сроки игроков не меняет.
 */
@Plugin(id = "heavenlydonatenotify", name = "HeavenlyDonateNotify", version = "1.0.0", authors = {"HeavenlyWeiner"},
        description = "Предупреждения об окончании временной привилегии Sponsor",
        dependencies = {@Dependency(id = "luckperms"), @Dependency(id = "limboauth", optional = true)})
public final class HeavenlyDonateNotify {

    private static final String HISTORY_FILE = "history.db";
    private static final Duration MAINTENANCE_PERIOD = Duration.ofMinutes(1);
    private static final Duration PRUNE_PERIOD = Duration.ofDays(1);
    private static final Duration PROBLEM_LOG_PERIOD = Duration.ofMinutes(5);

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDir;
    private final Clock clock = Clock.systemUTC();
    private final LogThrottle throttle = new LogThrottle(clock);

    private ActiveSettings settings;
    private LuckPermsSource source;
    private LoginGate gate;
    private CheckScheduler scheduler;
    private AdminActions actions;
    private volatile SqliteHistoryStore store;
    private volatile String storageError;
    private volatile Instant lastPrune = Instant.EPOCH;

    @Inject
    public HeavenlyDonateNotify(ProxyServer server, Logger logger, @DataDirectory Path dataDir) {
        this.server = server;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        settings = loadSettings();
        source = new LuckPermsSource();
        gate = new LoginGate(new LimboAuthProbe(server.getPluginManager(), logger), () -> settings.settings().requireLimboAuth());
        scheduler = new CheckScheduler(server, logger, settings, source, gate, clock);
        actions = new AdminActions(server, logger, dataDir, settings, scheduler, source, gate, new AdminActions.Storage() {
            @Override
            public Optional<HistoryStore> store() {
                return Optional.ofNullable(store);
            }

            @Override
            public Optional<String> error() {
                return Optional.ofNullable(storageError);
            }
        }, clock);
        CommandMeta meta = server.getCommandManager().metaBuilder("donatenotify").aliases("dnotify").plugin(this).build();
        server.getCommandManager().register(meta, new DonateNotifyCommand(actions));
        // Файлы и LuckPerms — в рабочем потоке, не в обработчике события.
        scheduler.execute(this::maintenance);
        scheduler.schedulePeriodic(this::maintenance, MAINTENANCE_PERIOD);
        scheduler.startTicks();
    }

    private ActiveSettings loadSettings() {
        try {
            return new ActiveSettings(SettingsLoader.load(dataDir));
        } catch (ConfigException | IOException | RuntimeException e) {
            logger.error("event=donate_notify_config_invalid error={}: действуют встроенные значения, исправьте config.yml "
                    + "и выполните /donatenotify reload", e.getMessage());
            ActiveSettings fallback = new ActiveSettings(SettingsLoader.defaults());
            fallback.markError(e.getMessage());
            return fallback;
        }
    }

    /** Открывает хранилище и подписку LuckPerms, если их ещё нет; раз в сутки чистит историю. */
    private void maintenance() {
        openStorage();
        subscribeLuckPerms();
        if (!lastPrune.plus(PRUNE_PERIOD).isAfter(clock.instant())) prune();
    }

    private void openStorage() {
        if (store != null) return;
        try {
            SqliteHistoryStore opened = new SqliteHistoryStore(dataDir.resolve(HISTORY_FILE));
            store = opened;
            storageError = null;
            scheduler.attachEngine(new NotificationEngine(settings::current, opened, clock, this::deliver, logger));
            logger.info("event=donate_notify_ready thresholds={} interval={}s login_delay={}s require_limboauth={}",
                    settings.settings().thresholds().describe(), settings.settings().checkInterval().toSeconds(),
                    settings.settings().loginDelay().toSeconds(), settings.settings().requireLimboAuth());
        } catch (StorageException e) {
            storageError = e.getMessage();
            if (throttle.allow("storage_open", PROBLEM_LOG_PERIOD)) {
                logger.error("event=donate_notify_storage_unavailable error={}: уведомления не отправляются", e.getMessage());
            }
        }
    }

    private void subscribeLuckPerms() {
        if (!source.subscribeRecalculate(this, scheduler::onRecalculate) && throttle.allow("luckperms_subscribe", PROBLEM_LOG_PERIOD)) {
            logger.warn("event=donate_notify_luckperms_unavailable: LuckPerms не загружен, проверки отложены");
        }
    }

    private void prune() {
        SqliteHistoryStore current = store;
        if (current == null) return;
        try {
            int removed = current.prune(clock.instant().minus(settings.settings().retention()));
            lastPrune = clock.instant();
            if (removed > 0) logger.info("event=donate_notify_history_pruned removed={}", removed);
        } catch (StorageException e) {
            logger.warn("event=donate_notify_history_prune_failed error={}", e.getMessage());
        }
    }

    /** Доставка только игроку онлайн, прошедшему вход; иначе запись об отправке снимается. */
    private boolean deliver(UUID uuid, Component message) {
        Player player = server.getPlayer(uuid).orElse(null);
        if (player == null || gate.verdict(player) != LoginGate.Verdict.ALLOW) return false;
        player.sendMessage(message);
        return true;
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        // Только первое подключение к игровому серверу в сессии; переходы между режимами покрывает периодическая проверка.
        if (event.getPreviousServer() == null) scheduler.onFirstServerConnect(event.getPlayer());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        scheduler.onDisconnect(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onProxyReload(ProxyReloadEvent event) {
        actions.reload();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        scheduler.close();
        source.close();
        SqliteHistoryStore current = store;
        store = null;
        if (current == null) return;
        try {
            current.close();
        } catch (StorageException e) {
            logger.warn("event=donate_notify_storage_close_failed error={}", e.getMessage());
        }
    }
}
