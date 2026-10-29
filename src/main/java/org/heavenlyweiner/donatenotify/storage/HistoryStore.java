package org.heavenlyweiner.donatenotify.storage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.heavenlyweiner.donatenotify.logic.Threshold;

/** Локальная история уведомлений и отслеживаемых сроков. Все методы потокобезопасны. */
public interface HistoryStore extends AutoCloseable {

    /** Атомарно записывает отправку; {@code false} — такое уведомление уже было. */
    boolean claim(UUID uuid, String group, Instant expiry, Threshold threshold, Instant now) throws StorageException;

    /** Снимает запись об отправке (доставка не удалась). */
    void release(UUID uuid, String group, Instant expiry, Threshold threshold) throws StorageException;

    List<SentRecord> sent(UUID uuid, String group) throws StorageException;

    /** Срок действует: запись ACTIVE; прочие ACTIVE-сроки той же группы становятся SUPERSEDED. */
    void observeActive(UUID uuid, String group, Instant expiry, Instant now) throws StorageException;

    /** Все ACTIVE-сроки группы становятся SUPERSEDED (появился бессрочный Sponsor). */
    void supersedeActive(UUID uuid, String group) throws StorageException;

    List<TrackedSubscription> tracked(UUID uuid, String group) throws StorageException;

    void setState(UUID uuid, String group, Instant expiry, TrackedState state) throws StorageException;

    /** Удаляет записи о сроках, закончившихся раньше {@code olderThan}; возвращает число удалённых строк. */
    int prune(Instant olderThan) throws StorageException;

    @Override
    void close() throws StorageException;
}
