package org.heavenlyweiner.donatenotify.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.heavenlyweiner.donatenotify.text.DurationParser;

/**
 * Доступ к дереву YAML по пути вида {@code checks.interval} с понятными ошибками.
 * Отсутствующий ключ берётся из встроенного образца конфига.
 */
final class ConfigNode {

    private final Map<?, ?> user;
    private final Map<?, ?> defaults;

    ConfigNode(Map<?, ?> user, Map<?, ?> defaults) {
        this.user = user == null ? Map.of() : user;
        this.defaults = defaults == null ? Map.of() : defaults;
    }

    Object raw(String path) {
        Object value = lookup(user, path);
        return value != null ? value : lookup(defaults, path);
    }

    private static Object lookup(Map<?, ?> root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
            if (current == null) return null;
        }
        return current;
    }

    String string(String path) throws ConfigException {
        Object value = raw(path);
        if (value == null) throw new ConfigException(path, "значение обязательно");
        if (value instanceof Map || value instanceof List) throw new ConfigException(path, "ожидалась строка");
        return String.valueOf(value);
    }

    boolean bool(String path) throws ConfigException {
        Object value = raw(path);
        if (value instanceof Boolean flag) return flag;
        String text = value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.equals("true") || text.equals("false")) return Boolean.parseBoolean(text);
        throw new ConfigException(path, "ожидалось true или false");
    }

    Duration duration(String path) throws ConfigException {
        String text = string(path);
        try {
            return DurationParser.parse(text);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(path, e.getMessage());
        }
    }

    List<String> stringList(String path) throws ConfigException {
        Object value = raw(path);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new ConfigException(path, "ожидался список");
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item == null || item instanceof Map || item instanceof List) {
                throw new ConfigException(path, "элементы списка должны быть строками");
            }
            result.add(String.valueOf(item));
        }
        return result;
    }
}
