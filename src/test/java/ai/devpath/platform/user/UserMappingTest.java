package ai.devpath.platform.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class UserMappingTest {

	@Autowired TestEntityManager em;
	@Autowired UserRepository users;
	@Autowired UserOauthIdentityRepository identities;
	@Autowired UserProfileRepository profiles;
	@Autowired OutboxRepository outbox;

	@Test
	void persistsUserIdentityProfileAndOutbox() throws Exception {
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
		String originalPayload = e.getPayload();
		OutboxEntry savedE = outbox.save(e);
		assertEquals("user.user.registered", savedE.getEventType());

		// I-1/P2-4: JSONB 왕복 동등성 — 이중 인코딩 없이 raw JSON이 그대로 복원되는지 검증
		// PostgreSQL JSONB normalizes whitespace, so compare as parsed JsonNode trees.
		// Double-encoding would produce a JSON string value (e.g. "{\"userId\":4}") which
		// would parse to a different tree than the original object.
		em.flush();
		em.clear();
		OutboxEntry reloaded = outbox.findById(savedE.getId()).orElseThrow();
		ObjectMapper mapper = new ObjectMapper();
		JsonNode expected = mapper.readTree(originalPayload);
		JsonNode actual = mapper.readTree(reloaded.getPayload());
		assertEquals(expected, actual,
				"outbox payload must round-trip as raw JSON without double-encoding");
	}
}
