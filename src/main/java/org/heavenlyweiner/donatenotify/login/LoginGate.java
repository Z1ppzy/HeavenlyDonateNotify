package org.heavenlyweiner.donatenotify.login;

import com.velocitypowered.api.proxy.Player;
import java.util.function.BooleanSupplier;

/**
 * Решает, завершил ли игрок вход. Velocity видит игрока ещё в лимбо LimboAuth (пароль, второй фактор),
 * где {@code getCurrentServer()} пуст. Подключение к игровому серверу считается входом только пока
 * LimboAuth загружен и готов: без него Velocity пускает всех без пароля.
 */
public final class LoginGate {

    /** Состояние LimboAuth. */
    public enum AuthState { READY, NOT_READY, MISSING, UNKNOWN }

    /** Можно ли сейчас отправлять игроку сообщения. */
    public enum Verdict { ALLOW, NOT_ON_BACKEND, AUTH_UNAVAILABLE }

    /** Источник состояния LimboAuth. */
    public interface AuthProbe {
        AuthState state();
    }

    private final AuthProbe probe;
    private final BooleanSupplier requireAuth;

    public LoginGate(AuthProbe probe, BooleanSupplier requireAuth) {
        this.probe = probe;
        this.requireAuth = requireAuth;
    }

    public Verdict verdict(Player player) {
        return decide(player.getCurrentServer().isPresent(), probe.state(), requireAuth.getAsBoolean());
    }

    public AuthState authState() {
        return probe.state();
    }

    /** UNKNOWN — LimboAuth есть, но старой сборки без проверки готовности: присутствия достаточно. */
    public static Verdict decide(boolean onBackend, AuthState auth, boolean requireAuth) {
        if (!onBackend) return Verdict.NOT_ON_BACKEND;
        if (!requireAuth) return Verdict.ALLOW;
        return switch (auth) {
            case READY, UNKNOWN -> Verdict.ALLOW;
            case NOT_READY, MISSING -> Verdict.AUTH_UNAVAILABLE;
        };
    }
}
