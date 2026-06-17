package ai.devpath.platform;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.devpath.platform.notification.NotificationRepository;
import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.outbox.OutboxRelay;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import ai.devpath.shared.event.UserRegisteredEvent;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

/**
 * 끝단간 통합 테스트: outbox 1행 → relayOnce() → Kafka → WelcomeNotificationConsumer → notifications WELCOME 1행.
 *
 * EmbeddedKafka가 실제 브로커 역할을 하며 bootstrapServersProperty로 spring.kafka.bootstrap-servers를 오버라이드한다.
 * WelcomeNotificationConsumer의 @KafkaListener도 동일 브로커를 구독한다.
 * Kafka 소비가 비동기이므로 Awaitility로 최대 10초 폴링해 notifications 행 생성을 대기한다.
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
    @Autowired NotificationRepository notifications;
    @Autowired JsonMapper jsonMapper;

    @Test
    void outboxToKafkaToWelcomeNotification_endToEnd() throws Exception {
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

        // 4. WelcomeNotificationConsumer가 비동기로 notifications에 WELCOME 행을 저장할 때까지 대기 (최대 10초)
        await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    long count = notifications.findAll().stream()
                            .filter(n -> n.getUserId().equals(userId) && "WELCOME".equals(n.getType()))
                            .count();
                    assertEquals(1L, count,
                            "notifications 테이블에 userId=" + userId + " WELCOME 행이 정확히 1개여야 한다");
                });
    }
}
