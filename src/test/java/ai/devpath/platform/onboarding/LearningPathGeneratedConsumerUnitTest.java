package ai.devpath.platform.onboarding;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import ai.devpath.shared.event.LearningPathGeneratedEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
class LearningPathGeneratedConsumerUnitTest {

  @Autowired LearningPathGeneratedConsumer consumer;
  @Autowired UserRepository users;
  @Autowired JsonMapper jsonMapper;

  @Test
  void pendingUserBecomesDone() throws Exception {
    User user = saveUser("PENDING");

    consumer.onLearningPathGenerated(payload(user.getId()));

    assertEquals("DONE", users.findById(user.getId()).orElseThrow().getOnboardingStatus());
  }

  @Test
  void inProgressUserBecomesDone() throws Exception {
    User user = saveUser("IN_PROGRESS");

    consumer.onLearningPathGenerated(payload(user.getId()));

    assertEquals("DONE", users.findById(user.getId()).orElseThrow().getOnboardingStatus());
  }

  @Test
  void doneUserStaysDone() throws Exception {
    User user = saveUser("DONE");

    consumer.onLearningPathGenerated(payload(user.getId()));

    assertEquals("DONE", users.findById(user.getId()).orElseThrow().getOnboardingStatus());
  }

  @Test
  void malformedPayloadIsSkipped() {
    assertDoesNotThrow(() -> consumer.onLearningPathGenerated("{not-json"));
  }

  private String payload(long userId) throws Exception {
    var event = new LearningPathGeneratedEvent(UUID.randomUUID(), Instant.now(),
        userId, 100L, "BACKEND_SPRING");
    return jsonMapper.writeValueAsString(event);
  }

  private User saveUser(String onboardingStatus) {
    User u = new User();
    u.setEmail("path-" + onboardingStatus.toLowerCase() + "-" + System.nanoTime() + "@example.com");
    u.setNickname("경로유저");
    u.setRole("LEARNER");
    u.setStatus("ACTIVE");
    u.setOnboardingStatus(onboardingStatus);
    return users.save(u);
  }
}
