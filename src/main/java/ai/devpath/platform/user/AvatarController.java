package ai.devpath.platform.user;

import ai.devpath.platform.user.dto.ProfileView;
import java.io.IOException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/users/me/avatar")
public class AvatarController {

  private final AvatarService service;

  public AvatarController(AvatarService service) {
    this.service = service;
  }

  @PostMapping
  public ProfileView upload(
      @AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file)
      throws IOException {
    return service.upload(
        Long.parseLong(jwt.getSubject()),
        file.getBytes(),
        file.getContentType(),
        file.getOriginalFilename());
  }

  @DeleteMapping
  public ProfileView delete(@AuthenticationPrincipal Jwt jwt) {
    return service.delete(Long.parseLong(jwt.getSubject()));
  }
}
