package ai.devpath.platform.auth;

import ai.devpath.platform.auth.crypto.TokenCipher;
import ai.devpath.platform.beta.BetaGate;
import ai.devpath.platform.config.BetaProperties;
import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.user.*;
import ai.devpath.shared.event.UserRegisteredEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class UserRegistrationService {

	public record OauthUser(String provider, String providerUserId, String email, String nickname, String accessToken) {}

	private final UserRepository users;
	private final UserOauthIdentityRepository identities;
	private final UserProfileRepository profiles;
	private final OutboxRepository outbox;
	private final TokenCipher cipher;
	private final JsonMapper jsonMapper;
	private final BetaProperties betaProps;

	public UserRegistrationService(UserRepository users, UserOauthIdentityRepository identities,
			UserProfileRepository profiles, OutboxRepository outbox, TokenCipher cipher, JsonMapper jsonMapper,
			BetaProperties betaProps) {
		this.users = users;
		this.identities = identities;
		this.profiles = profiles;
		this.outbox = outbox;
		this.cipher = cipher;
		this.jsonMapper = jsonMapper;
		this.betaProps = betaProps;
	}

	@Transactional
	public User registerOrFind(OauthUser oauth) {
		var existing = identities.findByProviderAndProviderUserId(oauth.provider(), oauth.providerUserId());
		if (existing.isPresent()) {
			return users.findById(existing.get().getUserId()).orElseThrow();
		}

		if (oauth.email() == null || oauth.email().isBlank()) {
			throw new MissingEmailException(oauth.provider());
		}

		var byEmail = users.findByEmail(oauth.email());
		boolean isNew = byEmail.isEmpty();
		User user;
		if (byEmail.isPresent()) {
			user = byEmail.get(); // 이메일 통합: 기존 User에 identity만 추가
		} else {
			user = createUser(oauth.email(), oauth.nickname());
		}

		UserOauthIdentity identity = new UserOauthIdentity();
		identity.setUserId(user.getId());
		identity.setProvider(oauth.provider());
		identity.setProviderUserId(oauth.providerUserId());
		if (oauth.accessToken() != null) identity.setAccessTokenEncrypted(cipher.encrypt(oauth.accessToken()));
		identity.setScope(scopeFor(oauth.provider()));
		identity.setLinkedAt(Instant.now());
		identities.save(identity);

		if (isNew) {
			writeOutbox(user, oauth.provider());
		}
		return user;
	}

	/** Staging release identities are ephemeral and must never enter user_oauth_identities. */
	@Transactional
	public User registerOrFindRelease(String email, String nickname) {
		if (email == null || email.isBlank()) throw new MissingEmailException("RELEASE");
		return users.findByEmail(email).orElseGet(() -> createUser(email, nickname));
	}

	private User createUser(String email, String nickname) {
		User user = new User();
		user.setEmail(email);
		user.setNickname(nickname);
		user.setRole(isAdmin(email) ? "ADMIN" : "LEARNER");
		user.setStatus("ACTIVE");
		user.setOnboardingStatus("PENDING");
		user = users.save(user);
		UserProfile profile = new UserProfile();
		profile.setUserId(user.getId());
		profiles.save(profile);
		return user;
	}

	private boolean isAdmin(String email) {
		String n = BetaGate.normalize(email);
		return n != null && betaProps.getAdminEmails().stream()
				.map(BetaGate::normalize).anyMatch(n::equals);
	}

	private static String scopeFor(String provider) {
		return switch (provider) {
			case "GOOGLE" -> "openid,profile,email";
			default -> "read:user,user:email";
		};
	}

	private void writeOutbox(User user, String provider) {
		UserRegisteredEvent event = new UserRegisteredEvent(
				UUID.randomUUID(), Instant.now(), user.getId(), provider, user.getEmail());
		OutboxEntry entry = new OutboxEntry();
		entry.setAggregateType("user");
		entry.setAggregateId(String.valueOf(user.getId()));
		entry.setEventType(UserRegisteredEvent.EVENT_TYPE);
		entry.setPayload(serialize(event));
		entry.setCreatedAt(Instant.now());
		outbox.save(entry);
	}

	private String serialize(UserRegisteredEvent event) {
		try {
			return jsonMapper.writeValueAsString(event);
		} catch (Exception e) {
			throw new IllegalStateException("UserRegisteredEvent 직렬화 실패", e);
		}
	}
}
