package ai.devpath.platform.user;

import ai.devpath.platform.auth.dto.UserSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

	private final UserRepository users;

	public UserController(UserRepository users) { this.users = users; }

	@GetMapping("/me")
	public ResponseEntity<UserSummary> me(@AuthenticationPrincipal Jwt jwt) {
		long userId = Long.parseLong(jwt.getSubject());
		return users.findById(userId)
				.map(u -> ResponseEntity.ok(UserSummary.of(u)))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}
}
