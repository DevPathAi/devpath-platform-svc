package ai.devpath.platform.auth.refresh;

import ai.devpath.platform.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenStore {

	public record Rotated(long userId, String newToken) {}

	private static final String PREFIX = "refresh:";
	private static final String BY_USER_PREFIX = "refresh:byUser:";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final StringRedisTemplate redis;
	private final AuthProperties props;

	public RefreshTokenStore(StringRedisTemplate redis, AuthProperties props) {
		this.redis = redis;
		this.props = props;
	}

	public String issue(long userId) {
		byte[] raw = new byte[32];
		RANDOM.nextBytes(raw);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		String tokenHash = hash(token);
		redis.opsForValue().set(PREFIX + tokenHash, String.valueOf(userId), props.getRefreshTtl());
		// P1: 계정 삭제 시 전량 무효화(revokeAll)를 위한 역인덱스. TTL은 refresh 토큰과 동일하게 맞춰 고아 인덱스 방지.
		String byUserKey = BY_USER_PREFIX + userId;
		redis.opsForSet().add(byUserKey, tokenHash);
		redis.expire(byUserKey, props.getRefreshTtl());
		return token;
	}

	public Optional<Long> validate(String token) {
		if (token == null || token.isBlank()) return Optional.empty();
		String v = redis.opsForValue().get(PREFIX + hash(token));
		return v == null ? Optional.empty() : Optional.of(Long.parseLong(v));
	}

	public Optional<Rotated> rotate(String oldToken) {
		Optional<Long> userId = validate(oldToken);
		if (userId.isEmpty()) return Optional.empty();
		redis.delete(PREFIX + hash(oldToken));
		String next = issue(userId.get());
		return Optional.of(new Rotated(userId.get(), next));
	}

	public void revoke(String token) {
		if (token != null && !token.isBlank()) redis.delete(PREFIX + hash(token));
	}

	/**
	 * 계정 soft-delete 시 해당 사용자의 발급된 모든 refresh 토큰을 무효화한다(역인덱스 기반).
	 */
	public void revokeAll(long userId) {
		String byUserKey = BY_USER_PREFIX + userId;
		Set<String> tokenHashes = redis.opsForSet().members(byUserKey);
		if (tokenHashes != null && !tokenHashes.isEmpty()) {
			for (String tokenHash : tokenHashes) {
				redis.delete(PREFIX + tokenHash);
			}
		}
		redis.delete(byUserKey);
	}

	private static String hash(String token) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
