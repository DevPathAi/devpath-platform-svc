package ai.devpath.platform.auth.refresh;

import ai.devpath.platform.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
	private static final String GRACE_PREFIX = "grace:";
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
		return v == null ? Optional.empty() : Optional.of(parseUserId(v));
	}

	/**
	 * 회전: 현행 토큰은 삭제 대신 짧은 유예 마커(grace:<userId>)로 교체해, 이미 전송 중인
	 * 동시 refresh(콜백 이중 부트스트랩·멀티탭)가 401로 세션을 파괴하지 않게 한다.
	 * 유예 토큰 재사용도 새 토큰을 발급하되 마커 TTL은 연장하지 않는다. grace<=0이면 기존 단일-사용.
	 */
	public Optional<Rotated> rotate(String oldToken) {
		if (oldToken == null || oldToken.isBlank()) return Optional.empty();
		String key = PREFIX + hash(oldToken);
		String value = redis.opsForValue().get(key);
		if (value == null) return Optional.empty();
		long userId = parseUserId(value);
		if (!value.startsWith(GRACE_PREFIX)) {
			Duration grace = props.getRefreshRotateGrace();
			if (grace == null || grace.isZero() || grace.isNegative()) {
				redis.delete(key);
			} else {
				redis.opsForValue().set(key, GRACE_PREFIX + userId, grace);
			}
		}
		return Optional.of(new Rotated(userId, issue(userId)));
	}

	private static long parseUserId(String value) {
		return Long.parseLong(
				value.startsWith(GRACE_PREFIX) ? value.substring(GRACE_PREFIX.length()) : value);
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
