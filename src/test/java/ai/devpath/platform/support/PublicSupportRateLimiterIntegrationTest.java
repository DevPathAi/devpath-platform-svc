package ai.devpath.platform.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PublicSupportRateLimiterIntegrationTest {
  @Autowired PublicSupportRateLimiter limiter;
  @Autowired StringRedisTemplate redis;

  @Test
  void appliesEmailAndIpLimitsAtomicallyWithExpiringHashedKeys() {
    String suffix = UUID.randomUUID().toString();
    String firstIp = "198.51.100." + Math.floorMod(suffix.hashCode(), 200);
    String secondIp = "203.0.113." + Math.floorMod(suffix.hashCode(), 200);
    String repeatedEmail = "repeat-" + suffix + "@example.com";
    List<String> keys = new ArrayList<>();
    keys.add(limiter.ipKey(firstIp));
    keys.add(limiter.ipKey(secondIp));
    keys.add(limiter.emailKey(repeatedEmail));

    try {
      for (int attempt = 0; attempt < 5; attempt++) {
        assertThat(limiter.allow(firstIp, repeatedEmail)).isTrue();
      }
      assertThat(limiter.allow(firstIp, repeatedEmail)).isFalse();

      for (int attempt = 0; attempt < 10; attempt++) {
        String email = "unique-" + attempt + "-" + suffix + "@example.com";
        keys.add(limiter.emailKey(email));
        assertThat(limiter.allow(secondIp, email)).isTrue();
      }
      String lastEmail = "blocked-" + suffix + "@example.com";
      keys.add(limiter.emailKey(lastEmail));
      assertThat(limiter.allow(secondIp, lastEmail)).isFalse();

      Long ttlSeconds = redis.getExpire(limiter.ipKey(firstIp));
      assertThat(ttlSeconds).isPositive().isLessThanOrEqualTo(3600L);
      assertThat(keys).allMatch(key -> !key.contains(firstIp)
          && !key.contains(secondIp)
          && !key.contains("@example.com"));
    } finally {
      redis.delete(keys);
    }
  }
}
