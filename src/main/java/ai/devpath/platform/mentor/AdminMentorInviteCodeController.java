package ai.devpath.platform.mentor;

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

  public AdminMentorInviteCodeController(MentorInviteCodeService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<MentorInviteCodeService.IssuedCode> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody CreateRequest request) {
    var issued = service.create(
        new MentorInviteCodeService.CreateCommand(
            request.label(), request.audience(), request.cohort(),
            request.expiresAt(), request.maxRedemptions()),
        userId(jwt));
    return ResponseEntity.status(HttpStatus.CREATED).body(issued);
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
}
