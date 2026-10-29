package org.heavenlyweiner.donatenotify.login;

import static org.heavenlyweiner.donatenotify.login.LoginGate.AuthState.MISSING;
import static org.heavenlyweiner.donatenotify.login.LoginGate.AuthState.NOT_READY;
import static org.heavenlyweiner.donatenotify.login.LoginGate.AuthState.READY;
import static org.heavenlyweiner.donatenotify.login.LoginGate.AuthState.UNKNOWN;
import static org.heavenlyweiner.donatenotify.login.LoginGate.Verdict.ALLOW;
import static org.heavenlyweiner.donatenotify.login.LoginGate.Verdict.AUTH_UNAVAILABLE;
import static org.heavenlyweiner.donatenotify.login.LoginGate.Verdict.NOT_ON_BACKEND;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.heavenlyweiner.donatenotify.login.LoginGate.AuthState;
import org.junit.jupiter.api.Test;

class LoginGateTest {

    @Test
    void limbo_player_is_never_notified() {
        for (AuthState state : AuthState.values()) {
            assertEquals(NOT_ON_BACKEND, LoginGate.decide(false, state, true));
            assertEquals(NOT_ON_BACKEND, LoginGate.decide(false, state, false));
        }
    }

    @Test
    void backend_connection_counts_only_with_ready_limboauth() {
        assertEquals(ALLOW, LoginGate.decide(true, READY, true));
        assertEquals(ALLOW, LoginGate.decide(true, UNKNOWN, true));
        assertEquals(AUTH_UNAVAILABLE, LoginGate.decide(true, NOT_READY, true));
        assertEquals(AUTH_UNAVAILABLE, LoginGate.decide(true, MISSING, true));
    }

    @Test
    void networks_without_limboauth_can_opt_out() {
        assertEquals(ALLOW, LoginGate.decide(true, MISSING, false));
        assertEquals(ALLOW, LoginGate.decide(true, NOT_READY, false));
    }
}
