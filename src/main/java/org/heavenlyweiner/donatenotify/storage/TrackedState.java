package org.heavenlyweiner.donatenotify.storage;

/** Состояние отслеживаемого срока Sponsor. */
public enum TrackedState {
    /** Срок действует, игрок его имел при последней проверке. */
    ACTIVE,
    /** Появился другой срок или бессрочный Sponsor: этот больше не актуален. */
    SUPERSEDED,
    /** Группа снята до срока: сообщение об окончании не нужно. */
    REVOKED,
    /** Срок наступил, сообщение об окончании отправлено. */
    EXPIRED_NOTIFIED
}
