package ai.devpath.platform.auth;

import ai.devpath.platform.auth.dto.LoginResponse;
import ai.devpath.platform.auth.dto.UserSummary;
import ai.devpath.platform.auth.jwt.JwtService;
import ai.devpath.platform.auth.refresh.RefreshTokenStore;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

	private final RefreshTokenStore refreshStore;
	private final JwtService jwt;
	private final RefreshCookies cookies;
	private final UserRepository users;

	public AuthController(RefreshTokenStore refreshStore, JwtService jwt, RefreshCookies cookies, UserRepository users) {
		this.refreshStore = refreshStore;
		this.jwt = jwt;
		this.cookies = cookies;
		this.users = users;
	}

	@PostMapping("/refresh")
	public ResponseEntity<?> refresh(
			@CookieValue(value = RefreshCookies.COOKIE_NAME, required = false) String refreshToken,
			HttpServletResponse response) {
		var rotated = refreshToken == null ? java.util.Optional.<RefreshTokenStore.Rotated>empty() : refreshStore.rotate(refreshToken);
		if (rotated.isEmpty()) return ResponseEntity.status(401).build();

		User user = users.findById(rotated.get().userId()).orElse(null);
		if (user == null) return ResponseEntity.status(401).build();

		String access = jwt.mintAccessToken(user.getId(), user.getRole());
		response.addHeader(HttpHeaders.SET_COOKIE, cookies.create(rotated.get().newToken()).toString());
		return ResponseEntity.ok(new LoginResponse(access, true, UserSummary.of(user)));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(
			@CookieValue(value = RefreshCookies.COOKIE_NAME, required = false) String refreshToken,
			HttpServletResponse response) {
		if (refreshToken != null) refreshStore.revoke(refreshToken);
		response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
		return ResponseEntity.noContent().build();
	}
}
