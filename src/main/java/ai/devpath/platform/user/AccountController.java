package ai.devpath.platform.user;

import ai.devpath.platform.auth.RefreshCookies;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class AccountController {

	private final AccountService accountService;
	private final RefreshCookies cookies;

	public AccountController(AccountService accountService, RefreshCookies cookies) {
		this.accountService = accountService;
		this.cookies = cookies;
	}

	@DeleteMapping("/me")
	public ResponseEntity<Void> deleteMe(@AuthenticationPrincipal Jwt jwt, HttpServletResponse response) {
		long userId = Long.parseLong(jwt.getSubject());
		accountService.softDelete(userId);
		response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
		return ResponseEntity.noContent().build();
	}
}
