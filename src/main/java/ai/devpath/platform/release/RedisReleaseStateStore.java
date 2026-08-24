package ai.devpath.platform.release;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RedisReleaseStateStore implements ReleaseStateStore {
	private static final String RUN_PREFIX = "mission-spine:release:run:";
	private static final String CODE_PREFIX = "mission-spine:release:oauth-code:";
	private static final String TOKEN_PREFIX = "mission-spine:release:oauth-token:";

	private final StringRedisTemplate redis;
	private final JsonMapper json;

	public RedisReleaseStateStore(StringRedisTemplate redis, JsonMapper json) {
		this.redis = redis;
		this.json = json;
	}

	@Override
	public Optional<ReleaseRunState> findRun(String candidateSpecSha256, String runKey) {
		return read(RUN_PREFIX + candidateSpecSha256 + ":" + hash(runKey), ReleaseRunState.class);
	}

	@Override
	public void saveRun(ReleaseRunState value, Duration ttl) {
		write(RUN_PREFIX + value.candidateSpecSha256() + ":" + hash(value.runKey()), value, ttl);
	}

	@Override
	public void saveAuthorizationCode(String code, ReleaseOAuthIdentity identity, Duration ttl) {
		write(CODE_PREFIX + hash(code), identity, ttl);
	}

	@Override
	public Optional<ReleaseOAuthIdentity> consumeAuthorizationCode(String code) {
		return readAndDelete(CODE_PREFIX + hash(code), ReleaseOAuthIdentity.class);
	}

	@Override
	public void saveAccessToken(String token, ReleaseOAuthIdentity identity, Duration ttl) {
		write(TOKEN_PREFIX + hash(token), identity, ttl);
	}

	@Override
	public Optional<ReleaseOAuthIdentity> consumeAccessToken(String token) {
		return readAndDelete(TOKEN_PREFIX + hash(token), ReleaseOAuthIdentity.class);
	}

	private void write(String key, Object value, Duration ttl) {
		try {
			redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
		} catch (Exception exception) {
			throw new IllegalStateException("release state serialization failed", exception);
		}
	}

	private <T> Optional<T> read(String key, Class<T> type) {
		return decode(redis.opsForValue().get(key), type);
	}

	private <T> Optional<T> readAndDelete(String key, Class<T> type) {
		return decode(redis.opsForValue().getAndDelete(key), type);
	}

	private <T> Optional<T> decode(String value, Class<T> type) {
		if (value == null) return Optional.empty();
		try {
			return Optional.of(json.readValue(value, type));
		} catch (Exception exception) {
			throw new IllegalStateException("release state deserialization failed", exception);
		}
	}

	private static String hash(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}
}
