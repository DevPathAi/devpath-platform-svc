package ai.devpath.platform.support;

import ai.devpath.platform.config.PublicSupportProperties;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class PublicSupportRateLimiter {
  private static final DefaultRedisScript<Long> LIMIT_SCRIPT = new DefaultRedisScript<>("""
      local ip = redis.call('INCR', KEYS[1])
      if ip == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
      local email = redis.call('INCR', KEYS[2])
      if email == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[1]) end
      if ip > tonumber(ARGV[2]) or email > tonumber(ARGV[3]) then return 0 end
      return 1
      """, Long.class);

  private final StringRedisTemplate redis;
  private final PublicSupportProperties properties;
  private final byte[] hmacSecret;

  public PublicSupportRateLimiter(StringRedisTemplate redis, PublicSupportProperties properties) {
    this.redis = redis;
    this.properties = properties;
    this.hmacSecret = String.valueOf(properties.getRateLimitHmacSecret())
        .getBytes(StandardCharsets.UTF_8);
    if (hmacSecret.length < 32) {
      throw new IllegalStateException("PUBLIC_SUPPORT_RATE_LIMIT_HMAC_SECRET must be >= 32 bytes");
    }
  }

  public boolean allow(String remoteIp, String email) {
    Long result = redis.execute(
        LIMIT_SCRIPT,
        List.of(ipKey(remoteIp), emailKey(email)),
        String.valueOf(properties.getRateLimitWindow().toMillis()),
        String.valueOf(properties.getRateLimitPerIp()),
        String.valueOf(properties.getRateLimitPerEmail()));
    return Long.valueOf(1L).equals(result);
  }

  String ipKey(String remoteIp) {
    return "support:public:ip:" + hmac(String.valueOf(remoteIp));
  }

  String emailKey(String email) {
    return "support:public:email:" + hmac(String.valueOf(email).trim().toLowerCase());
  }

  private String hmac(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacSecret, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    }
  }
}
