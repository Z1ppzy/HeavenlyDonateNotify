package org.heavenlyweiner.donatenotify.support;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.heavenlyweiner.donatenotify.logic.Notifier;

/** Запоминает доставленные сообщения как обычный текст; {@link #online} = false имитирует вышедшего игрока. */
public final class RecordingNotifier implements Notifier {

    public final List<String> messages = new CopyOnWriteArrayList<>();
    public volatile boolean online = true;

    @Override
    public boolean deliver(UUID uuid, Component message) {
        if (!online) return false;
        messages.add(PlainTextComponentSerializer.plainText().serialize(message));
        return true;
    }

    public String last() {
        return messages.get(messages.size() - 1);
    }
}
