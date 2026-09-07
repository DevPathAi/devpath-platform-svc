package ai.devpath.platform.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class MentorAccessRepositoryTest {

  @Autowired MentorAccessRepository accesses;
  @Autowired UserRepository users;

  @Test
  void batchClaimExcludesSoftDeletedAndInactiveUsers() {
    User live = user("live@example.com", "ACTIVE", null);
    User deleted = user("deleted@example.com", "ACTIVE", Instant.parse("2026-09-05T03:00:00Z"));
    User inactive = user("inactive@example.com", "PENDING", null);
    users.save(live);
    users.save(deleted);
    users.save(inactive);
    users.flush();

    accesses.save(MentorAccess.waitlisted(live.getId()));
    accesses.save(MentorAccess.waitlisted(deleted.getId()));
    accesses.save(MentorAccess.waitlisted(inactive.getId()));
    accesses.flush();

    assertThat(accesses.lockNextWaitlisted(10))
        .extracting(MentorAccess::getUserId)
        .contains(live.getId())
        .doesNotContain(deleted.getId(), inactive.getId());
  }

  private static User user(String email, String status, Instant deletedAt) {
    User user = new User();
    user.setEmail(email);
    user.setNickname(email.substring(0, email.indexOf('@')));
    user.setRole("LEARNER");
    user.setStatus(status);
    user.setOnboardingStatus("PENDING");
    user.setConsentStatus("PENDING");
    user.setDeletedAt(deletedAt);
    return user;
  }
}
