package ai.devpath.platform.auth;

import ai.devpath.platform.auth.crypto.TokenCipher;
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

	public UserRegistrationService(UserRepository users, UserOauthIdentityRepository identities,
			UserProfileRepository profiles, OutboxRepository outbox, TokenCipher cipher, JsonMapper jsonMapper) {
		this.users = users;
		this.identities = identities;
		this.profiles = profiles;
		this.outbox = outbox;
		this.cipher = cipher;
		this.jsonMapper = jsonMapper;
	}

	@Transactional
	public User registerOrFind(OauthUser oauth) {
		var existing = identities.findByProviderAndProviderUserId(oauth.provider(), oauth.providerUserId());
		if (existing.isPresent()) {
			return users.findById(existing.get().getUserId()).orElseThrow();
		}

		User user = new User();
		user.setEmail(oauth.email());
		user.setNickname(oauth.nickname());
		user.setRole("LEARNER");
		user.setStatus("ACTIVE");
		user.setOnboardingStatus("PENDING");
		user = users.save(user);

		UserOauthIdentity identity = new UserOauthIdentity();
		identity.setUserId(user.getId());
		identity.setProvider(oauth.provider());
		identity.setProviderUserId(oauth.providerUserId());
		if (oauth.accessToken() != null) identity.setAccessTokenEncrypted(cipher.encrypt(oauth.accessToken()));
		identity.setScope("read:user,user:email");
		identity.setLinkedAt(Instant.now());
		identities.save(identity);

		UserProfile profile = new UserProfile();
		profile.setUserId(user.getId());
		profiles.save(profile);

		writeOutbox(user, oauth.provider());
		return user;
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
