package ai.devpath.platform.notification;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import tools.jackson.databind.json.JsonMapper;
import ai.devpath.shared.event.UserRegisteredEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
class WelcomeNotificationConsumerTest {

	@Autowired WelcomeNotificationConsumer consumer;
	@Autowired NotificationRepository notifications;
	@Autowired UserRepository users;
	@Autowired JsonMapper om;

	@Test
	void createsWelcomeNotificationOnceIdempotently() throws Exception {
		User u = new User();
		u.setEmail("w" + System.nanoTime() + "@example.com");
		u.setNickname("지수"); u.setRole("LEARNER"); u.setStatus("ACTIVE"); u.setOnboardingStatus("PENDING");
		u = users.save(u);
		String payload = om.writeValueAsString(
				new UserRegisteredEvent(UUID.randomUUID(), Instant.now(), u.getId(), "GITHUB", u.getEmail()));

		consumer.onUserRegistered(payload);
		consumer.onUserRegistered(payload); // 중복

		final Long savedUserId = u.getId();
		assertEquals(1, notifications.findAll().stream()
				.filter(n -> n.getUserId().equals(savedUserId) && n.getType().equals("WELCOME")).count());
	}

	@Test
	void poisonPayloadIsSkippedWithoutThrowing() {
		// 역직렬화 불가 payload는 예외 없이 skip(다른 소비자와 동일, Kafka 무한재시도 방지).
		assertDoesNotThrow(() -> consumer.onUserRegistered("{ not-json"));
	}
}
