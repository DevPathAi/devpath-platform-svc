package ai.devpath.platform.mentor;

import ai.devpath.platform.config.AuthProperties;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/mentor/invite-codes")
public class AdminMentorInviteCodeController {
  private final MentorInviteCodeService service;
  private final AuthProperties auth;

  public AdminMentorInviteCodeController(
      MentorInviteCodeService service,
      AuthProperties auth) {
    this.service = service;
    this.auth = auth;
  }

  @PostMapping
  public ResponseEntity<IssuedCodeResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody CreateRequest request) {
    var issued = service.create(
        new MentorInviteCodeService.CreateCommand(
            request.label(), request.audience(), request.cohort(),
            request.expiresAt(), request.maxRedemptions()),
        userId(jwt));
    return ResponseEntity.status(HttpStatus.CREATED).body(IssuedCodeResponse.of(issued, auth.getWebUrl()));
  }

  @PostMapping("/{id}/disable")
  public ResponseEntity<Void> disable(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable long id,
      @RequestBody Map<String, String> body) {
    service.disable(id, userId(jwt), body.get("reason"));
    return ResponseEntity.noContent().build();
  }

  private static long userId(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }

  public record CreateRequest(
      String label,
      String audience,
      String cohort,
      Instant expiresAt,
      int maxRedemptions) {}

  public record IssuedCodeResponse(
      Long id,
      String code,
      String inviteUrl,
      Instant expiresAt,
      int maxRedemptions) {
    static IssuedCodeResponse of(MentorInviteCodeService.IssuedCode issued, String webUrl) {
      String base = webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
      String inviteUrl = base + "/login#invite=" + issued.code() + "&returnTo=%2Fmentor";
      return new IssuedCodeResponse(
          issued.id(), issued.code(), inviteUrl, issued.expiresAt(), issued.maxRedemptions());
    }
  }
}
