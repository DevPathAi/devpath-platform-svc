package ai.devpath.platform.auth.refresh;

import ai.devpath.platform.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenStore {

	public record Rotated(long userId, String newToken) {}

	private static final String PREFIX = "refresh:";
	private static final String BY_USER_PREFIX = "refresh:byUser:";
	private static final String GRACE_PREFIX = "grace:";
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

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
	 * 회전: 현행 토큰은 삭제 대신 유예 묘비(grace:&lt;userId&gt;:&lt;rotatedAtMillis&gt;)로 교체해, 이미 전송
	 * 중인 동시 refresh(콜백 이중 부트스트랩·멀티탭)가 401로 세션을 파괴하지 않게 한다. 묘비 TTL은
	 * refreshTtl로 두어 유예창 밖 재사용(탈취 신호)을 감지할 수 있게 남긴다. 유예창(now-rotatedAt&lt;=grace)
	 * 안 재사용은 정상으로 보고 새 토큰만 발급(마커 불변). 유예창 밖 재사용은 refresh-reuse-detection이
	 * 켜져 있으면 revokeAll로 전 세션을 폐기한다. grace&lt;=0이면 기존 단일-사용(묘비 없음).
	 */
	public Optional<Rotated> rotate(String oldToken) {
		if (oldToken == null || oldToken.isBlank()) return Optional.empty();
		String key = PREFIX + hash(oldToken);
		String value = redis.opsForValue().get(key);
		if (value == null) return Optional.empty();
		long userId = parseUserId(value);
		Duration grace = props.getRefreshRotateGrace();
		boolean graceEnabled = grace != null && !grace.isZero() && !grace.isNegative();

		if (!value.startsWith(GRACE_PREFIX)) {
			// 현행 토큰 회전
			if (!graceEnabled) {
				redis.delete(key); // 유예 비활성: 엄격 단일-사용
			} else {
				// 묘비: 회전 시각을 부가하고 TTL을 refreshTtl로 늘려, 유예창 밖 재사용을 감지 가능하게 남긴다.
				redis.opsForValue().set(key, GRACE_PREFIX + userId + ":" + System.currentTimeMillis(),
						props.getRefreshTtl());
			}
			return Optional.of(new Rotated(userId, issue(userId)));
		}

		// 이미 회전된(묘비) 토큰 재제시
		Long rotatedAt = parseRotatedAt(value); // 구(舊) 2-파트 마커면 null
		boolean withinGrace = rotatedAt == null
				|| (graceEnabled && (System.currentTimeMillis() - rotatedAt) <= grace.toMillis());
		if (withinGrace) {
			// 정상 동시-refresh: 마커 불변(연장 금지), 새 토큰만 발급
			return Optional.of(new Rotated(userId, issue(userId)));
		}

		// 유예창 밖 재사용 = 탈취 신호
		if (props.isRefreshReuseDetection()) {
			log.warn("refresh token reuse detected for user {} — revoking all sessions", userId);
			revokeAll(userId);
		}
		return Optional.empty();
	}

	private static long parseUserId(String value) {
		String s = value.startsWith(GRACE_PREFIX) ? value.substring(GRACE_PREFIX.length()) : value;
		int colon = s.indexOf(':');
		if (colon >= 0) s = s.substring(0, colon);
		return Long.parseLong(s);
	}

	private static Long parseRotatedAt(String value) {
		// "grace:<userId>:<millis>" 에서 millis 추출. 구 포맷 "grace:<userId>"면 null.
		int firstColon = value.indexOf(':');
		int secondColon = value.indexOf(':', firstColon + 1);
		if (secondColon < 0) return null;
		return Long.parseLong(value.substring(secondColon + 1));
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
