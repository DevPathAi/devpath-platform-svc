package ai.devpath.platform.onboarding;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import ai.devpath.shared.event.LearningPathGeneratedEvent;
import java.time.Duration;
import java.time.Instant;
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
@EmbeddedKafka(partitions = 1, topics = {"learning.path.generated"}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class LearningPathDoneTransitionIT {

  @Autowired UserRepository users;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JsonMapper jsonMapper;

  @Test
  void inProgressUserBecomesDoneWhenPathGeneratedEventArrives() throws Exception {
    User u = new User();
    u.setEmail("path-it-" + System.nanoTime() + "@example.com");
    u.setNickname("경로완료");
    u.setRole("LEARNER");
    u.setStatus("ACTIVE");
    u.setOnboardingStatus("IN_PROGRESS");
    u = users.save(u);
    final Long userId = u.getId();

    var event = new LearningPathGeneratedEvent(UUID.randomUUID(), Instant.now(),
        userId, 900L, "BACKEND_SPRING");
    kafka.send(LearningPathGeneratedEvent.EVENT_TYPE, String.valueOf(userId),
        jsonMapper.writeValueAsString(event));

    await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() ->
            assertEquals("DONE", users.findById(userId).orElseThrow().getOnboardingStatus()));
  }
}
