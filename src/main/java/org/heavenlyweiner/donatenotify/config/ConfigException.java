package org.heavenlyweiner.donatenotify.config;

/** Ошибка конфигурации с указанием ключа. */
public final class ConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    public ConfigException(String path, String problem) {
        super(path + ": " + problem);
    }
}
