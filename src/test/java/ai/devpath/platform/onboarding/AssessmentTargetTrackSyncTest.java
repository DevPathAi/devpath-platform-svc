package ai.devpath.platform.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserProfile;
import ai.devpath.platform.user.UserProfileRepository;
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

/**
 * 진단에서 고른 트랙이 프로필에 반영되는지 본다.
 *
 * <p>이전에는 user_profiles.target_track 을 읽는 로직이 한 곳도 없었다 —
 * 이용자가 마이페이지에서 고를 수는 있지만 아무 일도 하지 않는 값이었다.
 * 이제 진단이 출처이고 프로필이 그것을 따라간다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AssessmentTargetTrackSyncTest {

  @Autowired AssessmentCompletedConsumer consumer;
  @Autowired UserRepository users;
  @Autowired UserProfileRepository profiles;
  @Autowired JsonMapper jsonMapper;

  private long newUser() {
    User u = new User();
    u.setEmail("track-" + System.nanoTime() + "@example.com");
    u.setNickname("트랙");
    u.setRole("LEARNER");
    u.setStatus("ACTIVE");
    u.setOnboardingStatus("PENDING");
    return users.save(u).getId();
  }

  private String payload(long userId, String track) throws Exception {
    return jsonMapper.writeValueAsString(
        new AssessmentCompletedEvent(
            UUID.randomUUID(),
            Instant.now(),
            1L,
            userId,
            track,
            "BEGINNER",
            Map.of(),
            Instant.now()));
  }

  @Test
  void createsProfileWhenMissing() throws Exception {
    long userId = newUser();
    assertTrue(profiles.findById(userId).isEmpty(), "사전조건: 프로필 행이 없어야 한다");

    consumer.onAssessmentCompleted(payload(userId, "DEVOPS"));

    UserProfile p = profiles.findById(userId).orElseThrow();
    assertEquals("DEVOPS", p.getTargetTrack());
  }

  @Test
  void overwritesExistingTargetTrack() throws Exception {
    long userId = newUser();
    UserProfile seed = new UserProfile();
    seed.setUserId(userId);
    seed.setTargetTrack("BACKEND_SPRING");
    profiles.save(seed);

    consumer.onAssessmentCompleted(payload(userId, "FRONTEND_REACT"));

    assertEquals("FRONTEND_REACT", profiles.findById(userId).orElseThrow().getTargetTrack());
  }
}
