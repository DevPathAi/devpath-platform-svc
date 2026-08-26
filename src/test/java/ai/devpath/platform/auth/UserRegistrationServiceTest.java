package ai.devpath.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.devpath.platform.auth.UserRegistrationService.OauthUser;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserOauthIdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class UserRegistrationServiceTest {

	@Autowired UserRegistrationService service;
	@Autowired UserOauthIdentityRepository identities;
	@Autowired OutboxRepository outbox;
	@Autowired ai.devpath.platform.user.UserRepository users;

	@Test
	void newIdentityCreatesUserProfileIdentityAndOutboxEvent() {
		String providerUserId = "gh-" + System.nanoTime();
		long outboxBefore = outbox.count();
		OauthUser oauth = new OauthUser("GITHUB", providerUserId, "u-" + System.nanoTime() + "@example.com", "지수", "gho_token");

		User created = service.registerOrFind(oauth);

		assertTrue(created.getId() != null);
		assertEquals("PENDING", created.getOnboardingStatus());
		assertTrue(identities.findByProviderAndProviderUserId("GITHUB", providerUserId).isPresent());
		assertEquals(outboxBefore + 1, outbox.count(), "신규 가입 시 outbox 1행");
	}

	@Test
	void existingIdentityReturnsSameUserWithoutDuplicateEvent() {
		String providerUserId = "gh-" + System.nanoTime();
		OauthUser oauth = new OauthUser("GITHUB", providerUserId, "u-" + System.nanoTime() + "@example.com", "지수", "gho_token");
		User first = service.registerOrFind(oauth);
		long outboxAfterFirst = outbox.count();

		User second = service.registerOrFind(oauth);

		assertEquals(first.getId(), second.getId());
		assertEquals(outboxAfterFirst, outbox.count(), "기존 사용자는 이벤트 미발생");
	}

	@Test
	void emailMergeLinksIdentityToExistingUser() {
		long n = System.nanoTime();
		String email = "merge-" + n + "@example.com";
		User first = service.registerOrFind(
				new OauthUser("GITHUB", "gh-" + n, email, "지수", "t1"));
		long outboxAfterFirst = outbox.count();

		User second = service.registerOrFind(
				new OauthUser("GOOGLE", "goog-" + n, email, "Jisoo", "t2"));

		assertEquals(first.getId(), second.getId(), "같은 이메일은 동일 계정");
		assertEquals(outboxAfterFirst, outbox.count(), "통합(기존 계정)은 가입 이벤트 미발생");
		assertTrue(identities.findByProviderAndProviderUserId("GOOGLE", "goog-" + n).isPresent(),
				"Google identity가 기존 User에 연결");
	}

	@Test
	void missingEmailIsRejected() {
		long n = System.nanoTime();
		assertThrows(MissingEmailException.class, () -> service.registerOrFind(
				new OauthUser("GITHUB", "gh-" + n, null, "지수", "t")));
	}

	@Test
	void releaseFixtureCreatesOnlyTheUserAndNeverAPersistentOauthIdentity() {
		String email = "release-" + System.nanoTime() + "@staging.leva.invalid";
		long identityCount = identities.count();
		long outboxCount = outbox.count();

		User created = service.registerOrFindRelease(email, "Release Fixture");
		User replayed = service.registerOrFindRelease(email, "Release Fixture");

		assertEquals(created.getId(), replayed.getId());
		assertEquals(created.getId(), users.findByEmail(email).orElseThrow().getId());
		assertEquals(identityCount, identities.count(), "release identity must not be durable");
		assertEquals(outboxCount, outbox.count(), "release fixture must not publish signup events");
	}
}
