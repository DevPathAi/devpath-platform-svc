package ai.devpath.platform.mentor;

import static org.assertj.core.api.Assertions.assertThat;
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

  private static User user(long id, String email, String role, String status) {
    User user = mock(User.class);
    when(user.getId()).thenReturn(id);
    when(user.getEmail()).thenReturn(email);
    when(user.getRole()).thenReturn(role);
    when(user.getStatus()).thenReturn(status);
    return user;
  }
}
