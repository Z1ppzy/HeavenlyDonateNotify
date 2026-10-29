package org.heavenlyweiner.donatenotify.logic;

/** Что останется у игрока после окончания Sponsor. */
public enum RemainingStatus {
    /** Независимый бессрочный Premium. */
    PREMIUM,
    /** Только группы обычного игрока. */
    ORDINARY,
    /** Персонал, временные группы или неясные данные: нейтральный текст. */
    NEUTRAL
}
