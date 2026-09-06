package ai.devpath.platform.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.platform.beta.BetaAllowlistRepository;
import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.user.User;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MentorAccessServiceTest {

  private final MentorAccessRepository access = mock(MentorAccessRepository.class);
  private final BetaAllowlistRepository allowlist = mock(BetaAllowlistRepository.class);
  private final OutboxRepository outbox = mock(OutboxRepository.class);
  private final MentorAccessService service =
      new MentorAccessService(access, allowlist, outbox, JsonMapper.builder().build());

  @Test
  void firstUnlistedLoginCreatesWaitlistWithoutBlockingGeneralAccount() {
    User user = user(7L, "Person@Example.com", "LEARNER", "ACTIVE");
    when(access.findByUserId(7L)).thenReturn(Optional.empty());
    when(allowlist.existsByEmail("person@example.com")).thenReturn(false);
    when(access.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    MentorAccess result = service.ensureForLogin(user);

    assertThat(result.getStatus()).isEqualTo("WAITLISTED");
    assertThat(result.getSource()).isEqualTo("SELF");
    assertThat(user.getStatus()).isEqualTo("ACTIVE");
    verify(outbox).save(any(OutboxEntry.class));
  }

  @Test
  void allowlistedOrAdminLoginStartsActive() {
    User allowlisted = user(8L, "allowed@example.com", "LEARNER", "ACTIVE");
    when(access.findByUserId(8L)).thenReturn(Optional.empty());
    when(allowlist.existsByEmail("allowed@example.com")).thenReturn(true);
    when(access.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    assertThat(service.ensureForLogin(allowlisted).getStatus()).isEqualTo("ACTIVE");
    verify(outbox, never()).save(any());

    User admin = user(9L, "admin@example.com", "ADMIN", "ACTIVE");
    when(access.findByUserId(9L)).thenReturn(Optional.empty());
    assertThat(service.ensureForLogin(admin).getStatus()).isEqualTo("ACTIVE");
  }

  @Test
  void repeatedLoginReturnsExistingAccessWithoutDuplicateWriteOrEvent() {
    User user = user(10L, "same@example.com", "LEARNER", "ACTIVE");
    MentorAccess existing = MentorAccess.waitlisted(10L);
    when(access.findByUserId(10L)).thenReturn(Optional.of(existing));

    assertThat(service.ensureForLogin(user)).isSameAs(existing);
    verify(access, never()).save(any());
    verify(outbox, never()).save(any());
  }

  @Test
  void accessQueriesCoverActiveWaitingAndMissingRows() {
    MentorAccess active = MentorAccess.active(11L, "ADMIN");
    MentorAccess waiting = MentorAccess.waitlisted(12L);
    when(access.findByUserId(11L)).thenReturn(Optional.of(active));
    when(access.findByUserId(12L)).thenReturn(Optional.of(waiting));
    when(access.findByUserId(13L)).thenReturn(Optional.empty());

    assertThat(service.isActive(11L)).isTrue();
    assertThat(service.isActive(12L)).isFalse();
    assertThat(service.isActive(13L)).isFalse();
    assertThat(service.findForUser(12L)).isSameAs(waiting);
    assertThatThrownBy(() -> service.findForUser(13L))
        .isInstanceOf(MentorInviteCodeException.class)
        .extracting("code").isEqualTo("MENTOR_ACCESS_MISSING");
  }

  @Test
  void adminActivationUpdatesOnlyWaitingAccess() {
    User waitingUser = user(21L, "waiting@example.com", "LEARNER", "ACTIVE");
    User activeUser = user(22L, "active@example.com", "LEARNER", "ACTIVE");
    MentorAccess waiting = MentorAccess.waitlisted(21L);
    MentorAccess alreadyActive = MentorAccess.active(22L, "INVITE_CODE");
    when(access.findByUserId(21L)).thenReturn(Optional.of(waiting));
    when(access.findByUserId(22L)).thenReturn(Optional.of(alreadyActive));

    assertThat(service.activateByAdmin(waitingUser)).isSameAs(waiting);
    assertThat(waiting.getStatus()).isEqualTo("ACTIVE");
    assertThat(waiting.getSource()).isEqualTo("ADMIN");
    verify(access).save(waiting);

    assertThat(service.activateByAdmin(activeUser)).isSameAs(alreadyActive);
    verify(access, never()).save(alreadyActive);
  }

  private static User user(long id, String email, String role, String status) {
    User user = mock(User.class);
    when(user.getId()).thenReturn(id);
    when(user.getEmail()).thenReturn(email);
    when(user.getRole()).thenReturn(role);
    when(user.getStatus()).thenReturn(status);
    return user;
  }
}
