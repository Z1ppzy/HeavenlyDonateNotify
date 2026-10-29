package org.heavenlyweiner.donatenotify.login;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginManager;
import java.lang.reflect.Method;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Готовность LimboAuth через отражение, без зависимости от его классов: публичный {@code isAuthReady()}
 * есть в форке LimboAuth 1.1.18+; тем же методом пользуется AuthVelocityPlugin.
 */
public final class LimboAuthProbe implements LoginGate.AuthProbe {

    private static final String PLUGIN_ID = "limboauth";
    private static final String READY_METHOD = "isAuthReady";

    private final PluginManager plugins;
    private final Logger log;
    private volatile Method readyMethod;
    private volatile boolean warnedNoMethod;
    private volatile boolean warnedFailure;

    public LimboAuthProbe(PluginManager plugins, Logger log) {
        this.plugins = plugins;
        this.log = log;
    }

    @Override
    public LoginGate.AuthState state() {
        Optional<PluginContainer> container = plugins.getPlugin(PLUGIN_ID);
        Object instance = container.isPresent() ? container.get().getInstance().orElse(null) : null;
        if (instance == null) return LoginGate.AuthState.MISSING;
        Method method = readyMethod;
        if (method == null) {
            try {
                method = instance.getClass().getMethod(READY_METHOD);
                readyMethod = method;
            } catch (NoSuchMethodException e) {
                if (!warnedNoMethod) {
                    warnedNoMethod = true;
                    log.warn("event=donate_notify_limboauth_ready_unavailable: сборка LimboAuth без isAuthReady, готовность не проверяется");
                }
                return LoginGate.AuthState.UNKNOWN;
            }
        }
        try {
            return Boolean.TRUE.equals(method.invoke(instance)) ? LoginGate.AuthState.READY : LoginGate.AuthState.NOT_READY;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warnedFailure) {
                warnedFailure = true;
                log.warn("event=donate_notify_limboauth_ready_failed error_type={}", e.getClass().getSimpleName());
            }
            return LoginGate.AuthState.NOT_READY;
        }
    }
}
