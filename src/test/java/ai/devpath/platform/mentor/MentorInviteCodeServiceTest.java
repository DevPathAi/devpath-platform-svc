package ai.devpath.platform.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class MentorInviteCodeServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-05T03:00:00Z");

  private final MentorInviteCodeRepository codes = mock(MentorInviteCodeRepository.class);
  private final MentorInviteCodeRedemptionRepository redemptions =
      mock(MentorInviteCodeRedemptionRepository.class);
  private final MentorAccessRepository accesses = mock(MentorAccessRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final OutboxRepository outbox = mock(OutboxRepository.class);
  private final MentorInviteCodeHasher hasher = mock(MentorInviteCodeHasher.class);
  private final MentorInviteCodeService service = new MentorInviteCodeService(
      codes, redemptions, accesses, users, outbox, hasher,
      JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));

  private User user;
  private MentorAccess waiting;

  @BeforeEach
  void setUp() {
    user = mock(User.class);
    when(user.getId()).thenReturn(7L);
    when(user.getEmail()).thenReturn("person@example.com");
    when(users.findById(7L)).thenReturn(Optional.of(user));
    waiting = MentorAccess.waitlisted(7L);
    when(accesses.findByUserId(7L)).thenReturn(Optional.of(waiting));
    when(hasher.hash("one-time-code")).thenReturn("a".repeat(64));
  }

  @Test
  void validCodeAtomicallyActivatesAccessAndWritesAuditAndOutbox() {
    MentorInviteCode code = MentorInviteCode.create(
        "a".repeat(64), "2기 심사", "JUDGE", "cohort-2", NOW.plusSeconds(3600), 1, 99L);
    ReflectionTestUtils.setField(code, "id", 11L);
    when(codes.findLockedByCodeHash("a".repeat(64))).thenReturn(Optional.of(code));
    when(redemptions.existsByUserId(7L)).thenReturn(false);

    MentorAccess result = service.redeem(7L, "one-time-code");

    assertThat(result.getStatus()).isEqualTo("ACTIVE");
    assertThat(result.getSource()).isEqualTo("INVITE_CODE");
    assertThat(result.getInviteCodeId()).isEqualTo(11L);
    assertThat(code.getRedemptionCount()).isEqualTo(1);
    verify(redemptions).save(any(MentorInviteCodeRedemption.class));
    verify(outbox).save(any(OutboxEntry.class));
  }

  @Test
  void invalidExpiredAndExhaustedCodesFailWithoutChangingAccess() {
    when(codes.findLockedByCodeHash("a".repeat(64))).thenReturn(Optional.empty());
    assertCode("INVITE_CODE_INVALID");

    MentorInviteCode expired = MentorInviteCode.create(
        "a".repeat(64), "old", "JUDGE", "c", NOW.minusSeconds(1), 1, 99L);
    when(codes.findLockedByCodeHash("a".repeat(64))).thenReturn(Optional.of(expired));
    assertCode("INVITE_CODE_EXPIRED");

    MentorInviteCode exhausted = MentorInviteCode.create(
        "a".repeat(64), "used", "JUDGE", "c", NOW.plusSeconds(60), 1, 99L);
    exhausted.redeem(NOW);
    when(codes.findLockedByCodeHash("a".repeat(64))).thenReturn(Optional.of(exhausted));
    assertCode("INVITE_CODE_EXHAUSTED");

    assertThat(waiting.getStatus()).isEqualTo("WAITLISTED");
    verify(redemptions, never()).save(any());
    verify(outbox, never()).save(any());
  }

  @Test
  void alreadyActiveUserIsIdempotentAndDoesNotConsumeAnotherCode() {
    MentorAccess active = MentorAccess.active(7L, "BATCH");
    when(accesses.findByUserId(7L)).thenReturn(Optional.of(active));

    assertThat(service.redeem(7L, "one-time-code")).isSameAs(active);

    verify(hasher, never()).hash(any());
    verify(codes, never()).findLockedByCodeHash(any());
  }

  @Test
  void createReturnsRawCodeOnceWhileRepositoryReceivesOnlyItsHash() {
    AtomicReference<MentorInviteCode> stored = new AtomicReference<>();
    when(hasher.hash(any())).thenReturn("b".repeat(64));
    when(codes.save(any())).thenAnswer(invocation -> {
      MentorInviteCode code = invocation.getArgument(0);
      ReflectionTestUtils.setField(code, "id", 21L);
      stored.set(code);
      return code;
    });

    MentorInviteCodeService.IssuedCode issued = service.create(
        new MentorInviteCodeService.CreateCommand(
            "2기 심사", "JUDGE", "cohort-2", NOW.plusSeconds(3600), 5),
        99L);

    assertThat(issued.id()).isEqualTo(21L);
    assertThat(issued.code()).isNotBlank();
    assertThat(stored.get().getCodeHash()).isEqualTo("b".repeat(64));
    assertThat(stored.get().getCodeHash()).doesNotContain(issued.code());
  }

  private void assertCode(String code) {
    assertThatThrownBy(() -> service.redeem(7L, "one-time-code"))
        .isInstanceOf(MentorInviteCodeException.class)
        .extracting("code").isEqualTo(code);
  }
}
