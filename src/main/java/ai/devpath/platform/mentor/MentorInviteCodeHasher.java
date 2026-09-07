package ai.devpath.platform.mentor;

import ai.devpath.platform.config.MentorAccessProperties;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class MentorInviteCodeHasher {
  private final byte[] secret;

  public MentorInviteCodeHasher(MentorAccessProperties properties) {
    secret = String.valueOf(properties.getInviteCodeHmacSecret()).getBytes(StandardCharsets.UTF_8);
    if (secret.length < 32) {
      throw new IllegalStateException("MENTOR_INVITE_CODE_HMAC_SECRET must be >= 32 bytes");
    }
  }

  public String hash(String rawCode) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(rawCode.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    }
  }
}
