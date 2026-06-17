package ai.devpath.platform.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class UserMappingTest {

	@Autowired UserRepository users;
	@Autowired UserOauthIdentityRepository identities;
	@Autowired UserProfileRepository profiles;
	@Autowired OutboxRepository outbox;

	@Test
	void persistsUserIdentityProfileAndOutbox() {
		User u = new User();
		u.setEmail("t" + System.nanoTime() + "@example.com");
		u.setNickname("지수");
		u.setRole("LEARNER");
		u.setStatus("ACTIVE");
		u.setOnboardingStatus("PENDING");
		User saved = users.save(u);
		assertTrue(saved.getId() != null);

		UserOauthIdentity id = new UserOauthIdentity();
		id.setUserId(saved.getId());
		id.setProvider("GITHUB");
		id.setProviderUserId("gh-" + System.nanoTime());
		id.setLinkedAt(Instant.now());
		identities.save(id);
		assertTrue(identities.findByProviderAndProviderUserId(id.getProvider(), id.getProviderUserId()).isPresent());

		UserProfile p = new UserProfile();
		p.setUserId(saved.getId());
		profiles.save(p);

		OutboxEntry e = new OutboxEntry();
		e.setAggregateType("user");
		e.setAggregateId(String.valueOf(saved.getId()));
		e.setEventType("user.user.registered");
		e.setPayload("{\"userId\":" + saved.getId() + "}");
		e.setCreatedAt(Instant.now());
		OutboxEntry savedE = outbox.save(e);
		assertEquals("user.user.registered", savedE.getEventType());
	}
}
