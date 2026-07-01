package ai.devpath.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.outbox.OutboxRelay;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import ai.devpath.shared.event.UserRegisteredEvent;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

/**
 * 통합 테스트: outbox 1행 → relayOnce() → Kafka 발행까지 검증한다(발행측만).
 *
 * 이전에는 WelcomeNotificationConsumer까지 끝단간으로 검증했으나, 해당 컨슈머는
 * devpath-notification-svc로 이관되었다(2026-07-01). 소비측 커버리지는 그 레포의
 * WelcomeNotificationConsumerIT가 담당한다 — 이 테스트는 발행측(outbox→Kafka)만 책임진다.
 *
 * EmbeddedKafka가 실제 브로커 역할을 하며 bootstrapServersProperty로
 * spring.kafka.bootstrap-servers를 오버라이드한다. relay가 실제로 이 브로커에
 * publish하므로 브로커 자체는 여전히 필요하다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = 1,
        topics = {"user.user.registered"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class EventPropagationIT {

    @Autowired UserRepository users;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxRelay relay;
    @Autowired JsonMapper jsonMapper;

    @Test
    void outboxRelay_publishesUserRegisteredEvent() throws Exception {
        // 1. FK 만족용 실제 user 저장
        User user = new User();
        user.setEmail("it-" + System.nanoTime() + "@example.com");
        user.setNickname("통합테스트유저");
        user.setRole("LEARNER");
        user.setStatus("ACTIVE");
        user.setOnboardingStatus("PENDING");
        user = users.save(user);
        final Long userId = user.getId();

        // 2. UserRegisteredEvent payload를 직렬화해 outbox에 저장
        UserRegisteredEvent event = new UserRegisteredEvent(
                UUID.randomUUID(), Instant.now(), userId, "GITHUB", user.getEmail());
        String payload = jsonMapper.writeValueAsString(event);

        OutboxEntry entry = new OutboxEntry();
        entry.setAggregateType("user");
        entry.setAggregateId(String.valueOf(userId));
        entry.setEventType(UserRegisteredEvent.EVENT_TYPE);
        entry.setPayload(payload);
        entry.setCreatedAt(Instant.now());
        outbox.save(entry);

        // 3. relay가 outbox 행을 Kafka에 발행
        int published = relay.relayOnce();
        assertEquals(1, published, "relay는 1행을 발행해야 한다");

        // 4. 발행 성공 여부는 outbox 행의 published_at으로 검증(소비측은 더 이상 이 레포 책임 아님)
        OutboxEntry saved = outbox.findById(entry.getId()).orElseThrow();
        assertNotNull(saved.getPublishedAt(), "발행 성공 시 published_at이 채워져야 한다");
    }
}
