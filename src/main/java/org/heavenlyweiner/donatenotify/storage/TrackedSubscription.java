package org.heavenlyweiner.donatenotify.storage;

import java.time.Instant;
import java.util.UUID;

/** Срок Sponsor, который плагин видел у игрока. */
public record TrackedSubscription(UUID uuid, String group, Instant expiry, Instant firstSeen, Instant lastSeen,
                                  TrackedState state) {}
