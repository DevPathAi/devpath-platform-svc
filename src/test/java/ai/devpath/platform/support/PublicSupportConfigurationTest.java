package ai.devpath.platform.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PublicSupportConfigurationTest {
  @Test
  void productionConfigurationHasNoKnownRateLimitHmacFallback() throws IOException {
    try (var stream = getClass().getResourceAsStream("/application.yml")) {
      assertThat(stream).isNotNull();
      String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(source).contains(
          "rate-limit-hmac-secret: ${PUBLIC_SUPPORT_RATE_LIMIT_HMAC_SECRET:}");
      assertThat(source).doesNotContain("test-rate-limit-secret-32-bytes-000");
    }
  }
}
