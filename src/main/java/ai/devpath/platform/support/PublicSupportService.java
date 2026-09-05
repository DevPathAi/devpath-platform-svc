package ai.devpath.platform.support;

import ai.devpath.platform.support.dto.PublicSupportCreateRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PublicSupportService {
  private static final int EMAIL_MAX = 254;
  private static final int TITLE_MAX = 200;
  private static final int BODY_MAX = 5000;

  private final TurnstileVerifier turnstile;
  private final PublicSupportRateLimiter rateLimiter;
  private final SupportService support;
  private final Clock clock;

  @Autowired
  public PublicSupportService(
      TurnstileVerifier turnstile,
      PublicSupportRateLimiter rateLimiter,
      SupportService support) {
    this(turnstile, rateLimiter, support, Clock.systemUTC());
  }

  PublicSupportService(
      TurnstileVerifier turnstile,
      PublicSupportRateLimiter rateLimiter,
      SupportService support,
      Clock clock) {
    this.turnstile = turnstile;
    this.rateLimiter = rateLimiter;
    this.support = support;
    this.clock = clock;
  }

  public SupportRequest create(PublicSupportCreateRequest request, String remoteIp) {
    if (request == null) throw new IllegalArgumentException("request is required");
    String type = request.type() == null ? "" : request.type().trim();
    String email = String.valueOf(request.email()).trim().toLowerCase(Locale.ROOT);
    String title = request.title() == null ? "" : request.title().trim();
    String body = request.body() == null ? "" : request.body().trim();
    String token = request.turnstileToken() == null ? "" : request.turnstileToken().trim();
    if (!request.privacyConsent()) throw new IllegalArgumentException("privacyConsent is required");
    if (!"ERROR".equals(type) && !"INQUIRY".equals(type)) {
      throw new IllegalArgumentException("type must be ERROR or INQUIRY");
    }
    if (email.length() > EMAIL_MAX || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
      throw new IllegalArgumentException("valid email is required");
    }
    if (title.isEmpty() || title.length() > TITLE_MAX) {
      throw new IllegalArgumentException("title must be 1-" + TITLE_MAX + " characters");
    }
    if (body.isEmpty() || body.length() > BODY_MAX) {
      throw new IllegalArgumentException("body must be 1-" + BODY_MAX + " characters");
    }
    if (token.isEmpty()) throw new IllegalArgumentException("turnstileToken is required");

    boolean verified;
    try {
      verified = turnstile.verify(token, remoteIp);
    } catch (TurnstileUnavailableException e) {
      throw new PublicSupportException("TURNSTILE_UNAVAILABLE", "security verification unavailable");
    }
    if (!verified) {
      throw new PublicSupportException("TURNSTILE_FAILED", "security verification failed");
    }
    if (!rateLimiter.allow(remoteIp, email)) {
      throw new PublicSupportException("RATE_LIMITED", "too many public support requests");
    }
    return support.createPublic(email, type, title, body, Instant.now(clock));
  }
}
