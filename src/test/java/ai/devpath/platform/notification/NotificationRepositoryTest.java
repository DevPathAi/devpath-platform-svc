package ai.devpath.platform.notification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class NotificationRepositoryTest {

	@Autowired NotificationRepository repo;
	@Autowired UserRepository users;

	@Test
	void savesAndChecksExistenceByType() {
		User u = new User();
		u.setEmail("n" + System.nanoTime() + "@example.com");
		u.setNickname("지수"); u.setRole("LEARNER"); u.setStatus("ACTIVE"); u.setOnboardingStatus("PENDING");
		u = users.save(u); // FK 만족용 실제 user

		Notification n = new Notification();
		n.setUserId(u.getId());
		n.setType("WELCOME");
		n.setTitle("환영합니다");
		n.setCreatedAt(Instant.now());
		repo.save(n);

		assertTrue(repo.existsByUserIdAndType(u.getId(), "WELCOME"));
	}
}
