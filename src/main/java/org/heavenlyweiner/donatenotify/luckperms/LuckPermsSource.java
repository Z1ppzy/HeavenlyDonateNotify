package org.heavenlyweiner.donatenotify.luckperms;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.query.QueryOptions;
import org.heavenlyweiner.donatenotify.model.Assignment;
import org.heavenlyweiner.donatenotify.model.UserSnapshot;

/**
 * Чтение данных игрока из LuckPerms API в снимок. Только чтение: группы, права и сроки не меняются.
 * Блокирующие вызовы ({@code loadUser}, {@code lookupUniqueId}) выполняются с таймаутом и только из рабочего потока.
 */
public final class LuckPermsSource implements AutoCloseable {

    /** Снимок либо причина, по которой его нет. */
    public record Result(UserSnapshot snapshot, String error) {

        public boolean ok() {
            return snapshot != null;
        }

        static Result ok(UserSnapshot snapshot) {
            return new Result(snapshot, null);
        }

        static Result error(String error) {
            return new Result(null, error);
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private volatile EventSubscription<UserDataRecalculateEvent> subscription;

    public Optional<LuckPerms> api() {
        try {
            return Optional.of(LuckPermsProvider.get());
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
    }

    public boolean available() {
        return api().isPresent();
    }

    /** Снимок игрока: загруженный пользователь или загрузка из общей базы с последующей очисткой. */
    public Result snapshot(UUID uuid, String name) {
        LuckPerms luckPerms = api().orElse(null);
        if (luckPerms == null) return Result.error("LuckPerms недоступен");
        UserManager users = luckPerms.getUserManager();
        User user = users.getUser(uuid);
        boolean loadedHere = false;
        try {
            if (user == null) {
                user = users.loadUser(uuid, name).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                loadedHere = true;
            }
            if (user == null) return Result.error("LuckPerms не вернул данные игрока");
            return Result.ok(toSnapshot(luckPerms, user, uuid, name));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("загрузка прервана");
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            return Result.error("ошибка LuckPerms: " + e.getClass().getSimpleName());
        } finally {
            if (loadedHere && user != null) users.cleanupUser(user);
        }
    }

    /** UUID по нику из LuckPerms (для офлайн-игроков в командах). */
    public Optional<UUID> lookup(String name) {
        LuckPerms luckPerms = api().orElse(null);
        if (luckPerms == null) return Optional.empty();
        try {
            return Optional.ofNullable(luckPerms.getUserManager().lookupUniqueId(name).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Подписка на пересчёт данных игрока (в том числе после синхронизации из общей базы). */
    public boolean subscribeRecalculate(Object plugin, Consumer<UUID> listener) {
        if (subscription != null) return true;
        LuckPerms luckPerms = api().orElse(null);
        if (luckPerms == null) return false;
        subscription = luckPerms.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class,
                event -> listener.accept(event.getUser().getUniqueId()));
        return true;
    }

    @Override
    public void close() {
        EventSubscription<?> current = subscription;
        subscription = null;
        if (current != null) current.close();
    }

    private static UserSnapshot toSnapshot(LuckPerms luckPerms, User user, UUID uuid, String fallbackName) {
        QueryOptions query = luckPerms.getContextManager().getStaticQueryOptions();
        Map<String, Set<String>> staticContext = copy(luckPerms.getContextManager().getStaticContext().toMap());
        List<Assignment> assignments = new ArrayList<>();
        Set<String> groups = new HashSet<>();
        for (InheritanceNode node : user.getNodes(NodeType.INHERITANCE)) {
            if (!node.getValue()) continue; // негативный узел членства не даёт
            assignments.add(new Assignment(node.getGroupName(), node.hasExpiry() ? node.getExpiry() : null,
                    copy(node.getContexts().toMap())));
            groups.add(node.getGroupName());
        }
        Map<String, Set<String>> parents = new HashMap<>();
        for (String name : groups) {
            Group group = luckPerms.getGroupManager().getGroup(name);
            if (group == null) continue;
            parents.put(name, group.getInheritedGroups(query).stream().map(Group::getName).collect(Collectors.toSet()));
        }
        String username = user.getUsername() != null ? user.getUsername() : fallbackName != null ? fallbackName : uuid.toString();
        return new UserSnapshot(uuid, username, assignments, parents, staticContext);
    }

    private static Map<String, Set<String>> copy(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new HashMap<>();
        source.forEach((key, values) -> copy.put(key, Set.copyOf(values)));
        return copy;
    }
}
