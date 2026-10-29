package org.heavenlyweiner.donatenotify.logic;

import java.util.UUID;
import net.kyori.adventure.text.Component;

/** Доставка сообщения игроку. {@code false} — игрок недоступен, запись об отправке снимается. */
@FunctionalInterface
public interface Notifier {
    boolean deliver(UUID uuid, Component message);
}
