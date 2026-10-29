package org.heavenlyweiner.donatenotify.logic;

/** Вид доступа игрока к Sponsor на прокси. */
public enum AccessKind {
    /** Sponsor нет. */
    NONE,
    /** Временный: известен срок окончания. */
    TEMPORARY,
    /** Бессрочный: уведомления не нужны. */
    PERMANENT,
    /** Назначения нельзя оценить однозначно: плагин ничего не делает. */
    UNKNOWN
}
