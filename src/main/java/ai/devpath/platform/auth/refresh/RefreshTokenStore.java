package ai.devpath.platform.auth.refresh;

import ai.devpath.platform.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenStore {

	public record Rotated(long userId, String newToken) {}

	private static final String PREFIX = "refresh:";
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
		redis.opsForValue().set(PREFIX + hash(token), String.valueOf(userId), props.getRefreshTtl());
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

	private static String hash(String token) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
