package ai.devpath.platform.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import ai.devpath.shared.event.AssessmentCompletedEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
class AssessmentStatusConsumerUnitTest {

  @Autowired AssessmentCompletedConsumer consumer;
  @Autowired UserRepository users;
  @Autowired JsonMapper jsonMapper;

  @Test
  void doneUserStaysDone() throws Exception {
    User u = new User();
    u.setEmail("done-" + System.nanoTime() + "@example.com");
    u.setNickname("완료");
    u.setRole("LEARNER");
    u.setStatus("ACTIVE");
    u.setOnboardingStatus("DONE");
    u = users.save(u);
    final Long userId = u.getId();

    var event = new AssessmentCompletedEvent(UUID.randomUUID(), Instant.now(),
        1L, userId, "BACKEND_SPRING", "SENIOR", Map.of(), Instant.now());
    consumer.onAssessmentCompleted(jsonMapper.writeValueAsString(event));

    assertEquals("DONE", users.findById(userId).orElseThrow().getOnboardingStatus());
  }
}
