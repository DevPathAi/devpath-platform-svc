package ai.devpath.platform.mentor;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mentor-access")
public class MentorAccessController {
  private final MentorAccessService access;
  private final MentorInviteCodeService codes;

  public MentorAccessController(MentorAccessService access, MentorInviteCodeService codes) {
    this.access = access;
    this.codes = codes;
  }

  @GetMapping("/me")
  public AccessView status(@AuthenticationPrincipal Jwt jwt) {
    return AccessView.of(access.findForUser(userId(jwt)));
  }

  @PostMapping("/redeem")
  public AccessView redeem(
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody Map<String, String> body) {
    return AccessView.of(codes.redeem(userId(jwt), body.get("code")));
  }

  private static long userId(Jwt jwt) {
    return Long.parseLong(jwt.getSubject());
  }

  public record AccessView(String status, String source, String waitlistedAt, String activatedAt) {
    static AccessView of(MentorAccess row) {
      return new AccessView(
          row.getStatus(), row.getSource(), iso(row.getWaitlistedAt()), iso(row.getActivatedAt()));
    }

    private static String iso(java.time.Instant instant) {
      return instant == null ? null : instant.toString();
    }
  }
}
