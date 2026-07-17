package ai.devpath.platform.beta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import ai.devpath.shared.event.BetaAccessApprovedEvent;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AdminBetaServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final BetaAllowlistRepository allowlist = mock(BetaAllowlistRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final AdminBetaService service =
            new AdminBetaService(users, allowlist, outbox, JsonMapper.builder().build());

    private User pendingUser(long id, String email) {
        User u = new User();
        u.setEmail(email);
        u.setStatus("BETA_PENDING");
        // Reflection-free: use a spy to stub getId()
        User spy = spy(u);
        doReturn(id).when(spy).getId();
        return spy;
    }

    private User activeUser(long id, String email) {
        User u = new User();
        u.setEmail(email);
        u.setStatus("ACTIVE");
        User spy = spy(u);
        doReturn(id).when(spy).getId();
        return spy;
    }

    // ---------------------------------------------------------------
    // approveUser: happy path — BETA_PENDING → ACTIVE
    // ---------------------------------------------------------------

    @Test
    void approveUser_pendingUser_setsActive_insertsAllowlist_emitsEvent() {
        User user = pendingUser(42L, "Alice@Example.com");
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(allowlist.existsByEmail("alice@example.com")).thenReturn(false);
        when(users.save(any())).thenReturn(user);

        service.approveUser(42L);

        // status promoted
        assertThat(user.getStatus()).isEqualTo("ACTIVE");

        // allowlist saved with normalized email
        verify(allowlist).save(argThat(row -> "alice@example.com".equals(row.getEmail())));

        // outbox entry saved with correct eventType
        verify(outbox).save(argThat((OutboxEntry e) ->
                BetaAccessApprovedEvent.EVENT_TYPE.equals(e.getEventType())
                        && "42".equals(e.getAggregateId())
                        && "user".equals(e.getAggregateType())));
    }

    // ---------------------------------------------------------------
    // approveUser: idempotent — already ACTIVE → no-op
    // ---------------------------------------------------------------

    @Test
    void approveUser_alreadyActive_isNoOp() {
        User user = activeUser(7L, "already@active.com");
        when(users.findById(7L)).thenReturn(Optional.of(user));

        service.approveUser(7L);

        // status unchanged
        assertThat(user.getStatus()).isEqualTo("ACTIVE");

        // no allowlist insert, no outbox event
        verify(allowlist, never()).save(any());
        verify(outbox, never()).save(any());
    }

    // ---------------------------------------------------------------
    // approveUser: allowlist guard — already in allowlist, still sets status + emits event
    // ---------------------------------------------------------------

    @Test
    void approveUser_emailAlreadyInAllowlist_skipsAllowlistSave_butSetsActiveAndEmitsEvent() {
        User user = pendingUser(99L, "pre@approved.com");
        when(users.findById(99L)).thenReturn(Optional.of(user));
        when(allowlist.existsByEmail("pre@approved.com")).thenReturn(true); // already there

        service.approveUser(99L);

        assertThat(user.getStatus()).isEqualTo("ACTIVE");
        verify(allowlist, never()).save(any()); // no duplicate insert
        verify(outbox).save(any(OutboxEntry.class)); // event still emitted
    }

    // ---------------------------------------------------------------
    // preApprove: inserts allowlist when not yet present
    // ---------------------------------------------------------------

    @Test
    void preApprove_emailNotInAllowlist_savesAllowlistRow() {
        when(allowlist.existsByEmail("new@user.com")).thenReturn(false);
        when(users.findByEmail("new@user.com")).thenReturn(Optional.empty());

        service.preApprove("NEW@USER.COM", "admin1");

        verify(allowlist).save(argThat(row ->
                "new@user.com".equals(row.getEmail())
                        && "admin1".equals(row.getAddedBy())));
    }

    // ---------------------------------------------------------------
    // preApprove: email already in allowlist → no duplicate insert
    // ---------------------------------------------------------------

    @Test
    void preApprove_emailAlreadyInAllowlist_skipsAllowlistSave() {
        when(allowlist.existsByEmail("existing@user.com")).thenReturn(true);
        when(users.findByEmail("existing@user.com")).thenReturn(Optional.empty());

        service.preApprove("existing@user.com", "admin1");

        verify(allowlist, never()).save(any());
        verify(outbox, never()).save(any());
    }

    // ---------------------------------------------------------------
    // preApprove: existing BETA_PENDING user → approves them
    // ---------------------------------------------------------------

    @Test
    void preApprove_existingPendingUser_approvesAndEmitsEvent() {
        User user = pendingUser(55L, "pending@user.com");
        when(allowlist.existsByEmail("pending@user.com")).thenReturn(false);
        when(users.findByEmail("pending@user.com")).thenReturn(Optional.of(user));
        when(users.findById(55L)).thenReturn(Optional.of(user));
        when(allowlist.existsByEmail("pending@user.com"))
                .thenReturn(false)  // first call: pre-approve insert
                .thenReturn(true);  // second call inside approveUser: already inserted

        service.preApprove("pending@user.com", "admin2");

        assertThat(user.getStatus()).isEqualTo("ACTIVE");
        verify(outbox).save(argThat((OutboxEntry e) ->
                BetaAccessApprovedEvent.EVENT_TYPE.equals(e.getEventType())));
    }

    // ---------------------------------------------------------------
    // preApprove: existing ACTIVE user → does not re-approve
    // ---------------------------------------------------------------

    @Test
    void preApprove_existingActiveUser_doesNotReApprove() {
        User user = activeUser(33L, "active@user.com");
        when(allowlist.existsByEmail("active@user.com")).thenReturn(false);
        when(users.findByEmail("active@user.com")).thenReturn(Optional.of(user));

        service.preApprove("active@user.com", "admin3");

        // allowlist insert for pre-approve is fine
        verify(allowlist).save(any());
        // but no approval event
        verify(outbox, never()).save(any());
        // status stays ACTIVE
        assertThat(user.getStatus()).isEqualTo("ACTIVE");
    }
}
