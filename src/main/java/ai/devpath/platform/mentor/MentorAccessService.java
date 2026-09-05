package ai.devpath.platform.mentor;

import ai.devpath.platform.beta.BetaAllowlistRepository;
import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.user.User;
import ai.devpath.shared.event.MentorAccessWaitlistedEvent;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class MentorAccessService {
  private final MentorAccessRepository access;
  private final BetaAllowlistRepository allowlist;
  private final OutboxRepository outbox;
  private final JsonMapper jsonMapper;

  public MentorAccessService(
      MentorAccessRepository access,
      BetaAllowlistRepository allowlist,
      OutboxRepository outbox,
      JsonMapper jsonMapper) {
    this.access = access;
    this.allowlist = allowlist;
    this.outbox = outbox;
    this.jsonMapper = jsonMapper;
  }

  @Transactional
  public MentorAccess ensureForLogin(User user) {
    long userId = requireUserId(user);
    return access.findByUserId(userId).orElseGet(() -> createInitial(user, userId));
  }

  @Transactional(readOnly = true)
  public boolean isActive(long userId) {
    return access.findByUserId(userId)
        .map(row -> "ACTIVE".equals(row.getStatus()))
        .orElse(false);
  }

  @Transactional(readOnly = true)
  public MentorAccess findForUser(long userId) {
    return access.findByUserId(userId)
        .orElseThrow(() -> new MentorInviteCodeException("MENTOR_ACCESS_MISSING"));
  }

  @Transactional
  public MentorAccess activateByAdmin(User user) {
    MentorAccess row = ensureForLogin(user);
    if (!"ACTIVE".equals(row.getStatus())) {
      row.activate("ADMIN", null, null, Instant.now());
      access.save(row);
    }
    return row;
  }

  private MentorAccess createInitial(User user, long userId) {
    String email = normalize(user.getEmail());
    boolean immediatelyActive = "ADMIN".equals(user.getRole()) || allowlist.existsByEmail(email);
    MentorAccess row = access.save(immediatelyActive
        ? MentorAccess.active(userId, "ADMIN")
        : MentorAccess.waitlisted(userId));
    if (!immediatelyActive) writeWaitlistOutbox(userId, email, row.getWaitlistedAt());
    return row;
  }

  private void writeWaitlistOutbox(long userId, String email, Instant waitlistedAt) {
    Instant now = Instant.now();
    var event = new MentorAccessWaitlistedEvent(
        UUID.randomUUID(), now, userId, email, waitlistedAt);
    OutboxEntry entry = new OutboxEntry();
    entry.setAggregateType("mentor_access");
    entry.setAggregateId(String.valueOf(userId));
    entry.setEventType(event.eventType());
    entry.setPayload(serialize(event));
    entry.setCreatedAt(now);
    outbox.save(entry);
  }

  private String serialize(Object event) {
    try {
      return jsonMapper.writeValueAsString(event);
    } catch (Exception e) {
      throw new IllegalStateException("Mentor access event serialization failed", e);
    }
  }

  private static long requireUserId(User user) {
    if (user == null || user.getId() == null) {
      throw new IllegalArgumentException("persisted user is required");
    }
    return user.getId();
  }

  static String normalize(String email) {
    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }
}
