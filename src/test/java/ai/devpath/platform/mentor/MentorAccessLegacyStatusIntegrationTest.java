package ai.devpath.platform.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MentorAccessLegacyStatusIntegrationTest {
  @Autowired MentorAccessService service;
  @Autowired UserRepository users;
  @Autowired JdbcClient jdbc;

  @Test
  void legacyBetaPendingLoginPersistsGeneralAccessRestoration() {
    User user = new User();
    user.setEmail("legacy-login-" + System.nanoTime() + "@example.com");
    user.setNickname("레거시 사용자");
    user.setRole("LEARNER");
    user.setStatus("BETA_PENDING");
    user.setOnboardingStatus("DONE");
    user.setConsentStatus("PENDING");
    user = users.saveAndFlush(user);

    MentorAccess mentorAccess = service.ensureForLogin(user);

    assertThat(mentorAccess.getStatus()).isEqualTo("WAITLISTED");
    assertThat(jdbc.sql("SELECT status FROM users WHERE id=:userId")
        .param("userId", user.getId()).query(String.class).single()).isEqualTo("ACTIVE");
    assertThat(jdbc.sql("SELECT status FROM mentor_access WHERE user_id=:userId")
        .param("userId", user.getId()).query(String.class).single()).isEqualTo("WAITLISTED");
  }
}
