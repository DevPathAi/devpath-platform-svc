package ai.devpath.platform.onboarding;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import ai.devpath.shared.event.AssessmentCompletedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"learning.assessment.completed"}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class AssessmentStatusTransitionIT {

  @Autowired UserRepository users;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JsonMapper jsonMapper;

  @Test
  void pendingUserBecomesInProgress() throws Exception {
    User u = new User();
    u.setEmail("assess-" + System.nanoTime() + "@example.com");
    u.setNickname("진단유저");
    u.setRole("LEARNER");
    u.setStatus("ACTIVE");
    u.setOnboardingStatus("PENDING");
    u = users.save(u);
    final Long userId = u.getId();

    var event = new AssessmentCompletedEvent(UUID.randomUUID(), Instant.now(),
        100L, userId, "BACKEND_SPRING", "MID", Map.of("spring", 0.7), Instant.now());
    kafka.send(AssessmentCompletedEvent.EVENT_TYPE, String.valueOf(userId), jsonMapper.writeValueAsString(event));

    await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() ->
            assertEquals("IN_PROGRESS", users.findById(userId).orElseThrow().getOnboardingStatus()));
  }
}
