package ai.devpath.platform.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.platform.config.MentorAccessProperties;
import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MentorInviteBatchServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-05T03:00:00Z");
  private static final LocalDate DATE = LocalDate.parse("2026-09-05");

  private final MentorInviteBatchClaimRepository batches =
      mock(MentorInviteBatchClaimRepository.class);
  private final MentorAccessRepository accesses = mock(MentorAccessRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final OutboxRepository outbox = mock(OutboxRepository.class);
  private final MentorAccessProperties properties = properties();
  private final MentorInviteBatchService service = new MentorInviteBatchService(
      batches, accesses, users, outbox, properties,
      JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void claimedBatchActivatesBoundedWaitlistAndWritesPerUserAndCompletionEvents() {
    MentorAccess first = MentorAccess.waitlisted(1L);
    MentorAccess second = MentorAccess.waitlisted(2L);
    User firstUser = user(1L);
    User secondUser = user(2L);
    when(batches.claim(DATE, 2, 3)).thenReturn(Optional.of(31L));
    when(accesses.lockNextWaitlisted(anyInt()))
        .thenReturn(List.of(first, second))
        .thenReturn(List.of());
    when(users.findAllById(any())).thenReturn(List.of(firstUser, secondUser));

    MentorInviteBatchService.BatchRun result = service.run(DATE);

    assertThat(result.claimed()).isTrue();
    assertThat(result.activatedCount()).isEqualTo(2);
    assertThat(first.getStatus()).isEqualTo("ACTIVE");
    assertThat(first.getBatchId()).isEqualTo(31L);
    verify(batches).complete(31L, 2, NOW);
    verify(outbox).saveAll(org.mockito.ArgumentMatchers.argThat(entries -> {
      long size = 0;
      for (OutboxEntry ignored : entries) size += 1;
      return size == 3;
    }));
  }

  @Test
  void duplicateDailyClaimDoesNoWork() {
    when(batches.claim(DATE, 2, 3)).thenReturn(Optional.empty());

    MentorInviteBatchService.BatchRun result = service.run(DATE);

    assertThat(result.claimed()).isFalse();
    verify(accesses, never()).lockNextWaitlisted(anyInt());
    verify(outbox, never()).saveAll(any());
  }

  private static MentorAccessProperties properties() {
    MentorAccessProperties properties = new MentorAccessProperties();
    properties.setBatchChunkSize(2);
    properties.setBatchDailyCap(3);
    return properties;
  }

  private static User user(long id) {
    User user = mock(User.class);
    when(user.getId()).thenReturn(id);
    when(user.getEmail()).thenReturn("user" + id + "@example.com");
    return user;
  }
}
