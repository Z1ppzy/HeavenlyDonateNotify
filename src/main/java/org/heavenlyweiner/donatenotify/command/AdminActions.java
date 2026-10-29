package org.heavenlyweiner.donatenotify.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.heavenlyweiner.donatenotify.CheckScheduler;
import org.heavenlyweiner.donatenotify.config.ActiveSettings;
import org.heavenlyweiner.donatenotify.logic.SponsorAccess;
import org.heavenlyweiner.donatenotify.logic.Threshold;
import org.heavenlyweiner.donatenotify.login.LoginGate;
import org.heavenlyweiner.donatenotify.luckperms.LuckPermsSource;
import org.heavenlyweiner.donatenotify.storage.HistoryStore;
import org.heavenlyweiner.donatenotify.storage.SentRecord;
import org.heavenlyweiner.donatenotify.storage.StorageException;
import org.heavenlyweiner.donatenotify.storage.TrackedSubscription;
import org.slf4j.Logger;

/** Реализация команд администратора поверх планировщика, LuckPerms и хранилища. */
public final class AdminActions implements DonateNotifyCommand.Actions {

    /** Доступ к хранилищу, которое может быть ещё не открыто. */
    public interface Storage {
        Optional<HistoryStore> store();

        Optional<String> error();
    }

    private final ProxyServer server;
    private final Logger log;
    private final Path dataDir;
    private final ActiveSettings settings;
    private final CheckScheduler scheduler;
    private final LuckPermsSource source;
    private final LoginGate gate;
    private final Storage storage;
    private final Clock clock;

    public AdminActions(ProxyServer server, Logger log, Path dataDir, ActiveSettings settings, CheckScheduler scheduler,
                        LuckPermsSource source, LoginGate gate, Storage storage, Clock clock) {
        this.server = server;
        this.log = log;
        this.dataDir = dataDir;
        this.settings = settings;
        this.scheduler = scheduler;
        this.source = source;
        this.gate = gate;
        this.storage = storage;
        this.clock = clock;
    }

    @Override
    public Optional<String> reload() {
        Optional<String> error = settings.reload(dataDir);
        if (error.isPresent()) {
            log.error("event=donate_notify_reload_failed error={}: действует прежняя конфигурация", error.get());
        } else {
            scheduler.startTicks();
            log.info("event=donate_notify_reloaded thresholds={} interval={}s", settings.settings().thresholds().describe(),
                    settings.settings().checkInterval().toSeconds());
        }
        return error;
    }

    @Override
    public void status(CommandSource source, String player) {
        runAndReply(source, () -> StatusReport.render(collect(player)));
    }

    @Override
    public void preview(CommandSource source, String player, String token) {
        runAndReply(source, () -> {
            ActiveSettings.Bundle bundle = settings.current();
            Threshold threshold = bundle.thresholds().find(token).orElse(null);
            if (threshold == null) {
                return List.of(Component.text("Неизвестный порог «" + token + "». Доступны: "
                        + bundle.thresholds().describe() + ", expired", DonateNotifyCommand.ALERT));
            }
            Instant now = clock.instant();
            Optional<Player> online = server.getPlayer(player);
            UUID uuid = online.map(Player::getUniqueId).or(() -> this.source.lookup(player)).orElse(null);
            SponsorAccess access = null;
            if (uuid != null) {
                LuckPermsSource.Result data = this.source.snapshot(uuid, online.map(Player::getUsername).orElse(player));
                if (data.ok()) access = bundle.evaluator().evaluate(data.snapshot(), now);
            }
            String name = online.map(Player::getUsername).orElse(player);
            return PreviewBuilder.build(name, access, threshold, bundle, now);
        });
    }

    private void runAndReply(CommandSource source, Supplier<List<Component>> work) {
        scheduler.execute(() -> {
            try {
                source.sendMessage(Component.join(JoinConfiguration.newlines(), work.get()));
            } catch (RuntimeException e) {
                log.error("event=donate_notify_command_failed error_type={} message={}", e.getClass().getSimpleName(), e.getMessage());
                source.sendMessage(Component.text("Команда не выполнена: " + e.getClass().getSimpleName(), DonateNotifyCommand.ALERT));
            }
        });
    }

    private StatusReport.Input collect(String player) {
        Instant now = clock.instant();
        ActiveSettings.Bundle bundle = settings.current();
        String configError = settings.lastError().orElse(null);
        Optional<Player> online = server.getPlayer(player);
        UUID uuid = online.map(Player::getUniqueId).or(() -> source.lookup(player)).orElse(null);
        if (uuid == null) {
            return new StatusReport.Input(player, null, false, null, null, null, null, List.of(), List.of(),
                    storage.error().orElse(null), configError, source.available(), bundle, now);
        }
        String name = online.map(Player::getUsername).orElse(player);
        LuckPermsSource.Result data = source.snapshot(uuid, name);
        SponsorAccess access = data.ok() ? bundle.evaluator().evaluate(data.snapshot(), now) : null;
        LoginGate.Verdict verdict = online.map(gate::verdict).orElse(null);
        String serverName = online.flatMap(Player::getCurrentServer).map(c -> c.getServerInfo().getName()).orElse(null);
        String group = bundle.settings().sponsorGroup();
        List<TrackedSubscription> tracked = List.of();
        List<SentRecord> sent = List.of();
        String storageError = storage.error().orElse(null);
        Optional<HistoryStore> store = storage.store();
        if (store.isPresent()) {
            try {
                tracked = store.get().tracked(uuid, group);
                sent = store.get().sent(uuid, group);
            } catch (StorageException e) {
                storageError = e.getMessage();
            }
        }
        return new StatusReport.Input(name, uuid, online.isPresent(), serverName, verdict, data, access, tracked, sent,
                storageError, configError, source.available(), bundle, now);
    }

    @Override
    public List<String> thresholdTokens() {
        return settings.settings().thresholds().list().stream().map(Threshold::token).toList();
    }

    @Override
    public Collection<String> onlinePlayerNames() {
        return server.getAllPlayers().stream().map(Player::getUsername).toList();
    }
}
