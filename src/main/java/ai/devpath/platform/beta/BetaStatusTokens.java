package ai.devpath.platform.beta;

import ai.devpath.platform.config.BetaProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 미승인자 승인여부 조회 전용 단명 토큰(Redis opaque). refresh 토큰과 완전 분리된
 * 네임스페이스(beta-status:)를 쓰며, 실 API 인증에는 사용되지 않는다.
 */
@Component
public class BetaStatusTokens {

    private static final String PREFIX = "beta-status:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final BetaProperties props;

    public BetaStatusTokens(StringRedisTemplate redis, BetaProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public String issue(long userId) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        redis.opsForValue().set(PREFIX + hash(token), String.valueOf(userId), props.getStatusTtl());
        return token;
    }

    public Optional<Long> validate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String v = redis.opsForValue().get(PREFIX + hash(token));
        return v == null ? Optional.empty() : Optional.of(Long.parseLong(v));
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
