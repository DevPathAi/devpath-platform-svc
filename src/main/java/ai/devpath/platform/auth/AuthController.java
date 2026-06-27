package ai.devpath.platform.auth;

import ai.devpath.platform.auth.dto.LoginResponse;
import ai.devpath.platform.auth.dto.OauthTokenRequest;
import ai.devpath.platform.auth.dto.RefreshRequest;
import ai.devpath.platform.auth.dto.UserSummary;
import ai.devpath.platform.auth.jwt.JwtService;
import ai.devpath.platform.auth.refresh.RefreshTokenStore;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

	private final RefreshTokenStore refreshStore;
	private final JwtService jwt;
	private final RefreshCookies cookies;
	private final UserRepository users;
	private final AuthCodeStore authCodeStore;

	public AuthController(RefreshTokenStore refreshStore, JwtService jwt, RefreshCookies cookies,
			UserRepository users, AuthCodeStore authCodeStore) {
		this.refreshStore = refreshStore;
		this.jwt = jwt;
		this.cookies = cookies;
		this.users = users;
		this.authCodeStore = authCodeStore;
	}

	/**
	 * 모바일 PKCE 토큰 교환. 딥링크로 받은 일회용 {@code code}와 {@code code_verifier}를 검증해
	 * access(mint)+refresh(issue)를 바디로 반환한다. code는 1회성·단명이며, verifier가 발급 시
	 * 저장된 challenge와 S256으로 일치해야 한다. 어느 검증이라도 실패하면 401.
	 */
	@PostMapping("/oauth/token")
	public ResponseEntity<?> exchange(@RequestBody(required = false) OauthTokenRequest body) {
		if (body == null || body.code() == null || body.code().isBlank()
				|| body.codeVerifier() == null || body.codeVerifier().isBlank()) {
			return ResponseEntity.status(401).build();
		}
		Optional<AuthCodeStore.Consumed> consumed = authCodeStore.consume(body.code());
		if (consumed.isEmpty() || !Pkce.matches(body.codeVerifier(), consumed.get().codeChallenge())) {
			return ResponseEntity.status(401).build();
		}
		User user = users.findById(consumed.get().userId()).orElse(null);
		if (user == null) return ResponseEntity.status(401).build();

		String access = jwt.mintAccessToken(user.getId(), user.getRole());
		String refresh = refreshStore.issue(user.getId());
		return ResponseEntity.ok(new LoginResponse(access, refresh, false, UserSummary.of(user)));
	}

	@PostMapping("/refresh")
	public ResponseEntity<?> refresh(
			@CookieValue(value = RefreshCookies.COOKIE_NAME, required = false) String cookieToken,
			@RequestBody(required = false) RefreshRequest body,
			HttpServletResponse response) {
		// 모바일(토큰-바디) 우선, 없으면 웹(쿠키)으로 폴백.
		boolean fromBody = body != null && body.refreshToken() != null && !body.refreshToken().isBlank();
		String refreshToken = fromBody ? body.refreshToken() : cookieToken;

		Optional<RefreshTokenStore.Rotated> rotated =
				refreshToken == null ? Optional.empty() : refreshStore.rotate(refreshToken);
		if (rotated.isEmpty()) return ResponseEntity.status(401).build();

		User user = users.findById(rotated.get().userId()).orElse(null);
		if (user == null) return ResponseEntity.status(401).build();

		String access = jwt.mintAccessToken(user.getId(), user.getRole());
		if (fromBody) {
			// 모바일: 회전된 신규 refresh를 바디로 반환(네이티브는 쿠키를 못 읽으므로 회전 불일치 방지). 쿠키 미설정.
			return ResponseEntity.ok(new LoginResponse(access, rotated.get().newToken(), false, UserSummary.of(user)));
		}
		// 웹: 신규 refresh는 HttpOnly 쿠키로만(바디 비노출).
		response.addHeader(HttpHeaders.SET_COOKIE, cookies.create(rotated.get().newToken()).toString());
		return ResponseEntity.ok(new LoginResponse(access, null, true, UserSummary.of(user)));
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
