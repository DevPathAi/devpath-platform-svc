package ai.devpath.platform.beta;

import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import ai.devpath.shared.event.BetaAccessApprovedEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AdminBetaService {

    private final UserRepository users;
    private final BetaAllowlistRepository allowlist;
    private final OutboxRepository outbox;
    private final JsonMapper jsonMapper;

    public AdminBetaService(UserRepository users, BetaAllowlistRepository allowlist,
            OutboxRepository outbox, JsonMapper jsonMapper) {
        this.users = users;
        this.allowlist = allowlist;
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Approve an existing user by ID.
     * Idempotent: if the user is already ACTIVE, this is a no-op (no allowlist insert, no event).
     * Otherwise: sets status=ACTIVE, inserts email into beta_allowlist (guarded), emits
     * BetaAccessApprovedEvent via outbox.
     */
    @Transactional
    public void approveUser(long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));
        if (BetaGate.ACTIVE.equals(user.getStatus())) {
            return; // already active — no-op
        }
        String email = BetaGate.normalize(user.getEmail());
        if (!allowlist.existsByEmail(email)) {
            BetaAllowlist row = new BetaAllowlist();
            row.setEmail(email);
            row.setNote("approved via admin");
            allowlist.save(row);
        }
        user.setStatus(BetaGate.ACTIVE);
        users.save(user);
        writeApprovedOutbox(user, email);
    }

    /**
     * Pre-approve an email address (may or may not have an existing user).
     * Inserts email into beta_allowlist (idempotent).
     * If a User with that email already exists and is NOT ACTIVE, promotes them via approveUser.
     *
     * Note on @Transactional self-invocation: approveUser is called directly (not via proxy).
     * Both methods are write-only and will participate in the same surrounding transaction —
     * exception in either will roll back the whole unit. Separate @Transactional on approveUser
     * has no additional effect here but is kept for standalone call correctness.
     */
    @Transactional
    public void preApprove(String rawEmail, String addedBy) {
        String email = BetaGate.normalize(rawEmail);
        if (!allowlist.existsByEmail(email)) {
            BetaAllowlist row = new BetaAllowlist();
            row.setEmail(email);
            row.setAddedBy(addedBy);
            allowlist.save(row);
        }
        users.findByEmail(email)
                .filter(u -> !BetaGate.ACTIVE.equals(u.getStatus()))
                .ifPresent(u -> approveUser(u.getId()));
    }

    private void writeApprovedOutbox(User user, String email) {
        var event = new BetaAccessApprovedEvent(
                UUID.randomUUID(), Instant.now(), user.getId(), email);
        OutboxEntry entry = new OutboxEntry();
        entry.setAggregateType("user");
        entry.setAggregateId(String.valueOf(user.getId()));
        entry.setEventType(BetaAccessApprovedEvent.EVENT_TYPE);
        entry.setPayload(serialize(event));
        entry.setCreatedAt(Instant.now());
        outbox.save(entry);
    }

    private String serialize(Object event) {
        try {
            return jsonMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("Beta 승인 이벤트 직렬화 실패", e);
        }
    }
}
