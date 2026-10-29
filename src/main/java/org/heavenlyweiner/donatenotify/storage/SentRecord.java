package org.heavenlyweiner.donatenotify.storage;

import java.time.Instant;
import java.util.UUID;
import org.heavenlyweiner.donatenotify.logic.Threshold;

/** Запись об отправленном уведомлении: игрок, группа, срок окончания и порог. */
public record SentRecord(UUID uuid, String group, Instant expiry, Threshold threshold, Instant sentAt) {}
