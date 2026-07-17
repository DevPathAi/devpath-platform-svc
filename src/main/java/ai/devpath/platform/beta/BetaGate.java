package ai.devpath.platform.beta;

import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import ai.devpath.shared.event.BetaWaitlistRegisteredEvent;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class BetaGate {

    static final String PENDING = "BETA_PENDING";
    static final String ACTIVE = "ACTIVE";

    private final BetaAllowlistRepository allowlist;
    private final UserRepository users;
    private final OutboxRepository outbox;
    private final JsonMapper jsonMapper;

    public BetaGate(BetaAllowlistRepository allowlist, UserRepository users,
            OutboxRepository outbox, JsonMapper jsonMapper) {
        this.allowlist = allowlist;
        this.users = users;
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public boolean admit(User user) {
        boolean allowed = allowlist.existsByEmail(normalize(user.getEmail()));
        if (allowed) {
            if (PENDING.equals(user.getStatus())) {
                user.setStatus(ACTIVE);
                users.save(user);
            }
            return true;
        }
        boolean firstTime = !PENDING.equals(user.getStatus());
        if (firstTime) {
            user.setStatus(PENDING);
            users.save(user);
            writeWaitlistOutbox(user);
        }
        return false;
    }

    private void writeWaitlistOutbox(User user) {
        long userId = user.getId() != null ? user.getId() : 0L;
        var event = new BetaWaitlistRegisteredEvent(
                UUID.randomUUID(), Instant.now(), userId, normalize(user.getEmail()));
        OutboxEntry entry = new OutboxEntry();
        entry.setAggregateType("user");
        entry.setAggregateId(String.valueOf(userId));
        entry.setEventType(BetaWaitlistRegisteredEvent.EVENT_TYPE);
        entry.setPayload(serialize(event));
        entry.setCreatedAt(Instant.now());
        outbox.save(entry);
    }

    private String serialize(Object event) {
        try {
            return jsonMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("Beta 이벤트 직렬화 실패", e);
        }
    }
}
