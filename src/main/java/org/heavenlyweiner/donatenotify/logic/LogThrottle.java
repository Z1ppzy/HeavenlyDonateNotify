package org.heavenlyweiner.donatenotify.logic;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** Не чаще одного сообщения в журнал на ключ за период. */
public final class LogThrottle {

    private static final int MAX_KEYS = 5000;

    private final Clock clock;
    private final ConcurrentHashMap<String, Long> lastLogged = new ConcurrentHashMap<>();

    public LogThrottle(Clock clock) {
        this.clock = clock;
    }

    public boolean allow(String key, Duration period) {
        long now = clock.millis();
        Long previous = lastLogged.get(key);
        if (previous != null && now - previous < period.toMillis()) return false;
        if (lastLogged.size() > MAX_KEYS) lastLogged.clear();
        lastLogged.put(key, now);
        return true;
    }
}
