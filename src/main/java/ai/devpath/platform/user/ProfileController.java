package ai.devpath.platform.user;

import ai.devpath.platform.user.dto.ProfileUpdateRequest;
import ai.devpath.platform.user.dto.ProfileView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/me/profile")
public class ProfileController {

  private final UserProfileService service;

  public ProfileController(UserProfileService service) {
    this.service = service;
  }

  @GetMapping
  public ProfileView get(@AuthenticationPrincipal Jwt jwt) {
    return service.get(Long.parseLong(jwt.getSubject()));
  }

  @PutMapping
  public ProfileView update(
      @AuthenticationPrincipal Jwt jwt, @RequestBody ProfileUpdateRequest body) {
    return service.update(Long.parseLong(jwt.getSubject()), body);
  }
}
