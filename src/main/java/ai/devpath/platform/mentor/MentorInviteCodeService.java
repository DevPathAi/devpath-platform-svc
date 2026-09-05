package ai.devpath.platform.mentor;

import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import ai.devpath.shared.event.MentorAccessActivatedEvent;
import java.time.Clock;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class MentorInviteCodeService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final MentorInviteCodeRepository codes;
  private final MentorInviteCodeRedemptionRepository redemptions;
  private final MentorAccessRepository accesses;
  private final UserRepository users;
  private final OutboxRepository outbox;
  private final MentorInviteCodeHasher hasher;
  private final JsonMapper jsonMapper;
  private final Clock clock;

  @Autowired
  public MentorInviteCodeService(
      MentorInviteCodeRepository codes,
      MentorInviteCodeRedemptionRepository redemptions,
      MentorAccessRepository accesses,
      UserRepository users,
      OutboxRepository outbox,
      MentorInviteCodeHasher hasher,
      JsonMapper jsonMapper) {
    this(codes, redemptions, accesses, users, outbox, hasher, jsonMapper, Clock.systemUTC());
  }

  MentorInviteCodeService(
      MentorInviteCodeRepository codes,
      MentorInviteCodeRedemptionRepository redemptions,
      MentorAccessRepository accesses,
      UserRepository users,
      OutboxRepository outbox,
      MentorInviteCodeHasher hasher,
      JsonMapper jsonMapper,
      Clock clock) {
    this.codes = codes;
    this.redemptions = redemptions;
    this.accesses = accesses;
    this.users = users;
    this.outbox = outbox;
    this.hasher = hasher;
    this.jsonMapper = jsonMapper;
    this.clock = clock;
  }

  @Transactional
  public MentorAccess redeem(long userId, String rawCode) {
    User user = users.findById(userId)
        .orElseThrow(() -> new MentorInviteCodeException("INVITE_CODE_INVALID"));
    MentorAccess access = accesses.findByUserId(userId)
        .orElseThrow(() -> new MentorInviteCodeException("MENTOR_ACCESS_MISSING"));
    if ("ACTIVE".equals(access.getStatus())) return access;
    if (rawCode == null || rawCode.isBlank()) {
      throw new MentorInviteCodeException("INVITE_CODE_INVALID");
    }

    MentorInviteCode code = codes.findLockedByCodeHash(hasher.hash(rawCode))
        .orElseThrow(() -> new MentorInviteCodeException("INVITE_CODE_INVALID"));
    Instant now = Instant.now(clock);
    if (!code.isEnabled()) throw new MentorInviteCodeException("INVITE_CODE_DISABLED");
    if (code.isExpiredAt(now)) throw new MentorInviteCodeException("INVITE_CODE_EXPIRED");
    if (code.isExhausted()) throw new MentorInviteCodeException("INVITE_CODE_EXHAUSTED");
    if (redemptions.existsByUserId(userId)) return access;

    code.redeem(now);
    codes.save(code);
    redemptions.save(MentorInviteCodeRedemption.of(code.getId(), userId, now));
    access.activate("INVITE_CODE", null, code.getId(), now);
    accesses.save(access);
    writeActivatedOutbox(user, access, now);
    return access;
  }

  @Transactional
  public IssuedCode create(CreateCommand command, long actorId) {
    if (command == null || command.label() == null || command.label().isBlank()
        || command.label().length() > 100) {
      throw new IllegalArgumentException("label is required");
    }
    if (!"JUDGE".equals(command.audience()) && !"MENTOR".equals(command.audience())) {
      throw new IllegalArgumentException("audience must be JUDGE or MENTOR");
    }
    if (command.cohort() == null || command.cohort().isBlank()
        || command.cohort().length() > 64) {
      throw new IllegalArgumentException("cohort is required");
    }
    Instant now = Instant.now(clock);
    if (command.expiresAt() == null || !command.expiresAt().isAfter(now)) {
      throw new IllegalArgumentException("expiresAt must be in the future");
    }
    if (command.maxRedemptions() < 1 || command.maxRedemptions() > 1000) {
      throw new IllegalArgumentException("maxRedemptions must be 1-1000");
    }

    byte[] entropy = new byte[32];
    RANDOM.nextBytes(entropy);
    String rawCode = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    MentorInviteCode stored = codes.save(MentorInviteCode.create(
        hasher.hash(rawCode), command.label().trim(), command.audience(), command.cohort().trim(),
        command.expiresAt(), command.maxRedemptions(), actorId));
    return new IssuedCode(stored.getId(), rawCode, stored.getExpiresAt(), stored.getMaxRedemptions());
  }

  @Transactional
  public void disable(long codeId, long actorId, String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 255) {
      throw new IllegalArgumentException("disable reason is required");
    }
    MentorInviteCode code = codes.findLockedById(codeId)
        .orElseThrow(() -> new MentorInviteCodeException("INVITE_CODE_INVALID"));
    if (code.isEnabled()) {
      code.disable(actorId, reason.trim(), Instant.now(clock));
      codes.save(code);
    }
  }

  private void writeActivatedOutbox(User user, MentorAccess access, Instant now) {
    var event = new MentorAccessActivatedEvent(
        UUID.randomUUID(), now, user.getId(), MentorAccessService.normalize(user.getEmail()),
        access.getSource(), access.getActivatedAt(), access.getBatchId());
    OutboxEntry entry = new OutboxEntry();
    entry.setAggregateType("mentor_access");
    entry.setAggregateId(String.valueOf(user.getId()));
    entry.setEventType(event.eventType());
    entry.setPayload(serialize(event));
    entry.setCreatedAt(now);
    outbox.save(entry);
  }

  private String serialize(Object event) {
    try {
      return jsonMapper.writeValueAsString(event);
    } catch (Exception e) {
      throw new IllegalStateException("Mentor invite event serialization failed", e);
    }
  }

  public record CreateCommand(
      String label,
      String audience,
      String cohort,
      Instant expiresAt,
      int maxRedemptions) {}

  /** code는 이 응답에서만 한 번 노출한다. */
  public record IssuedCode(Long id, String code, Instant expiresAt, int maxRedemptions) {}
}
