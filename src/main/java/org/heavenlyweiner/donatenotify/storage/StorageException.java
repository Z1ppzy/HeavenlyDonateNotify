package org.heavenlyweiner.donatenotify.storage;

/** Ошибка локального хранилища истории. Уведомления при ней не отправляются. */
public final class StorageException extends Exception {

    private static final long serialVersionUID = 1L;

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
