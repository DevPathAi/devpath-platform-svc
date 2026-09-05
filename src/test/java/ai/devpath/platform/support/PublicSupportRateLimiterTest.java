package ai.devpath.platform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.devpath.platform.config.PublicSupportProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class PublicSupportRateLimiterTest {

  @Test
  void hashesIpAndNormalizedEmailWithoutPuttingRawValuesInKeys() {
    PublicSupportProperties properties = properties();
    PublicSupportRateLimiter limiter = new PublicSupportRateLimiter(
        org.mockito.Mockito.mock(StringRedisTemplate.class), properties);

    String ipKey = limiter.ipKey("203.0.113.10");
    String emailKey = limiter.emailKey(" Person@Example.com ");

    assertThat(ipKey).startsWith("support:public:ip:").doesNotContain("203.0.113.10");
    assertThat(emailKey).startsWith("support:public:email:")
        .doesNotContain("Person").doesNotContain("example.com");
    assertThat(emailKey).isEqualTo(limiter.emailKey("person@example.com"));
  }

  @Test
  void refusesShortHmacSecrets() {
    PublicSupportProperties properties = properties();
    properties.setRateLimitHmacSecret("too-short");
    assertThatThrownBy(() -> new PublicSupportRateLimiter(
        org.mockito.Mockito.mock(StringRedisTemplate.class), properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32 bytes");
  }

  private static PublicSupportProperties properties() {
    PublicSupportProperties properties = new PublicSupportProperties();
    properties.setRateLimitHmacSecret("0123456789abcdef0123456789abcdef");
    return properties;
  }
}
