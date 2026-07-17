package ai.devpath.platform.beta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BetaGateTest {

    private final BetaAllowlistRepository allow = mock(BetaAllowlistRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final BetaGate gate = new BetaGate(allow, users, outbox, JsonMapper.builder().build());

    private User user(String email, String status) {
        User u = new User();
        u.setEmail(email);
        u.setStatus(status);
        return u;
    }

    @Test
    void allowlistedUser_isAdmitted_noWaitlistEvent() {
        when(allow.existsByEmail("a@b.com")).thenReturn(true);
        assertThat(gate.admit(user("A@b.com", "ACTIVE"))).isTrue();
        verify(outbox, never()).save(any());
    }

    @Test
    void unlistedNewUser_isHeld_setsPending_andEmitsEvent() {
        when(allow.existsByEmail("x@y.com")).thenReturn(false);
        User u = user("x@y.com", "ACTIVE");
        assertThat(gate.admit(u)).isFalse();
        assertThat(u.getStatus()).isEqualTo("BETA_PENDING");
        verify(users).save(u);
        verify(outbox, times(1)).save(any(OutboxEntry.class));
    }

    @Test
    void alreadyPendingUser_reLogin_emitsNoDuplicateEvent() {
        when(allow.existsByEmail("x@y.com")).thenReturn(false);
        User u = user("x@y.com", "BETA_PENDING");
        assertThat(gate.admit(u)).isFalse();
        verify(outbox, never()).save(any());
    }

    @Test
    void preApprovedPendingUser_isPromotedToActive() {
        when(allow.existsByEmail("x@y.com")).thenReturn(true);
        User u = user("x@y.com", "BETA_PENDING");
        assertThat(gate.admit(u)).isTrue();
        assertThat(u.getStatus()).isEqualTo("ACTIVE");
        verify(users).save(u);
    }
}
