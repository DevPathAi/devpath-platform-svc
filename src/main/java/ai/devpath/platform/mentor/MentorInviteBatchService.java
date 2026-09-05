package ai.devpath.platform.mentor;

import ai.devpath.platform.config.MentorAccessProperties;
import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import ai.devpath.shared.event.MentorAccessActivatedEvent;
import ai.devpath.shared.event.MentorInviteBatchCompletedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class MentorInviteBatchService {
  private final MentorInviteBatchClaimRepository batches;
  private final MentorAccessRepository accesses;
  private final UserRepository users;
  private final OutboxRepository outbox;
  private final MentorAccessProperties properties;
  private final JsonMapper jsonMapper;
  private final Clock clock;

  @Autowired
  public MentorInviteBatchService(
      MentorInviteBatchClaimRepository batches,
      MentorAccessRepository accesses,
      UserRepository users,
      OutboxRepository outbox,
      MentorAccessProperties properties,
      JsonMapper jsonMapper) {
    this(batches, accesses, users, outbox, properties, jsonMapper, Clock.systemUTC());
  }

  MentorInviteBatchService(
      MentorInviteBatchClaimRepository batches,
      MentorAccessRepository accesses,
      UserRepository users,
      OutboxRepository outbox,
      MentorAccessProperties properties,
      JsonMapper jsonMapper,
      Clock clock) {
    this.batches = batches;
    this.accesses = accesses;
    this.users = users;
    this.outbox = outbox;
    this.properties = properties;
    this.jsonMapper = jsonMapper;
    this.clock = clock;
  }

  @Transactional
  public BatchRun run(LocalDate date) {
    int chunkSize = properties.getBatchChunkSize();
    int dailyCap = properties.getBatchDailyCap();
    validateLimits(chunkSize, dailyCap);
    var claimed = batches.claim(date, chunkSize, dailyCap);
    if (claimed.isEmpty()) return new BatchRun(null, date, false, 0);

    long batchId = claimed.get();
    int activatedCount = 0;
    List<OutboxEntry> events = new ArrayList<>();
    while (activatedCount < dailyCap) {
      int nextSize = Math.min(chunkSize, dailyCap - activatedCount);
      List<MentorAccess> claimedAccesses = accesses.lockNextWaitlisted(nextSize);
      if (claimedAccesses.isEmpty()) break;

      Map<Long, User> usersById = new HashMap<>();
      users.findAllById(claimedAccesses.stream().map(MentorAccess::getUserId).toList())
          .forEach(user -> usersById.put(user.getId(), user));
      if (usersById.size() != claimedAccesses.size()) {
        throw new IllegalStateException("mentor waitlist contains a missing user");
      }

      Instant now = Instant.now(clock);
      for (MentorAccess access : claimedAccesses) {
        User user = usersById.get(access.getUserId());
        access.activate("BATCH", batchId, null, now);
        events.add(activatedEvent(user, access, now));
      }
      accesses.saveAll(claimedAccesses);
      activatedCount += claimedAccesses.size();
    }

    Instant completedAt = Instant.now(clock);
    batches.complete(batchId, activatedCount, completedAt);
    events.add(completedEvent(batchId, date, activatedCount, completedAt));
    outbox.saveAll(events);
    return new BatchRun(batchId, date, true, activatedCount);
  }

  private OutboxEntry activatedEvent(User user, MentorAccess access, Instant occurredAt) {
    var event = new MentorAccessActivatedEvent(
        UUID.randomUUID(), occurredAt, user.getId(),
        MentorAccessService.normalize(user.getEmail()), access.getSource(),
        access.getActivatedAt(), access.getBatchId());
    return outboxEntry("mentor_access", String.valueOf(user.getId()),
        event.eventType(), event, occurredAt);
  }

  private OutboxEntry completedEvent(
      long batchId, LocalDate date, int activatedCount, Instant occurredAt) {
    var event = new MentorInviteBatchCompletedEvent(
        UUID.randomUUID(), occurredAt, batchId, date, activatedCount);
    return outboxEntry("mentor_invite_batch", String.valueOf(batchId),
        event.eventType(), event, occurredAt);
  }

  private OutboxEntry outboxEntry(
      String aggregateType, String aggregateId, String eventType, Object event, Instant occurredAt) {
    OutboxEntry entry = new OutboxEntry();
    entry.setAggregateType(aggregateType);
    entry.setAggregateId(aggregateId);
    entry.setEventType(eventType);
    entry.setPayload(serialize(event));
    entry.setCreatedAt(occurredAt);
    return entry;
  }

  private String serialize(Object event) {
    try {
      return jsonMapper.writeValueAsString(event);
    } catch (Exception e) {
      throw new IllegalStateException("Mentor invite batch event serialization failed", e);
    }
  }

  private static void validateLimits(int chunkSize, int dailyCap) {
    if (chunkSize < 1 || chunkSize > 500) {
      throw new IllegalArgumentException("batch chunk size must be 1-500");
    }
    if (dailyCap < 1 || dailyCap > 5000) {
      throw new IllegalArgumentException("batch daily cap must be 1-5000");
    }
  }

  public record BatchRun(Long batchId, LocalDate date, boolean claimed, int activatedCount) {}
}
