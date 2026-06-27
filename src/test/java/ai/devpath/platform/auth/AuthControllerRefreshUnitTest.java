package ai.devpath.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.devpath.platform.auth.dto.LoginResponse;
import ai.devpath.platform.auth.dto.RefreshRequest;
import ai.devpath.platform.auth.jwt.JwtService;
import ai.devpath.platform.auth.refresh.RefreshTokenStore;
import ai.devpath.platform.config.AuthProperties;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 순수 단위테스트(Spring 컨텍스트·Redis 불요). 모바일 토큰-바디 계약과 웹 쿠키 계약의
 * 분기를 검증한다. 쿠키 회전·Redis 연동의 통합 검증은 {@link AuthControllerTest}.
 */
class AuthControllerRefreshUnitTest {

	private RefreshTokenStore store;
	private JwtService jwt;
	private UserRepository users;
	private AuthController controller;

	@BeforeEach
	void setUp() {
		store = mock(RefreshTokenStore.class);
		jwt = mock(JwtService.class);
		users = mock(UserRepository.class);
		AuthProperties props = new AuthProperties();
		controller = new AuthController(store, jwt, new RefreshCookies(props), users);

		User u = mock(User.class);
		when(u.getId()).thenReturn(7L);
		when(u.getRole()).thenReturn("LEARNER");
		when(u.getEmail()).thenReturn("learner@devpath.ai");
		when(u.getNickname()).thenReturn("지수");
		when(u.getOnboardingStatus()).thenReturn("DONE");
		when(users.findById(7L)).thenReturn(Optional.of(u));
		when(jwt.mintAccessToken(7L, "LEARNER")).thenReturn("acc");
	}

	@Test
	void bodyRefreshReturnsRotatedRefreshTokenInBodyAndNoCookie() {
		when(store.rotate("rt-1")).thenReturn(Optional.of(new RefreshTokenStore.Rotated(7L, "rt-2")));
		MockHttpServletResponse res = new MockHttpServletResponse();

		ResponseEntity<?> r = controller.refresh(null, new RefreshRequest("rt-1"), res);

		assertEquals(200, r.getStatusCode().value());
		LoginResponse body = (LoginResponse) r.getBody();
		assertNotNull(body);
		assertEquals("acc", body.accessToken());
		assertEquals("rt-2", body.refreshToken(), "모바일: 회전된 신규 refresh를 바디로 반환");
		assertEquals("7", body.user().id());
		assertNull(res.getHeader("Set-Cookie"), "모바일: 쿠키 미설정");
	}

	@Test
	void cookieRefreshKeepsTokenInCookieAndNotInBody() {
		when(store.rotate("ck-1")).thenReturn(Optional.of(new RefreshTokenStore.Rotated(7L, "ck-2")));
		MockHttpServletResponse res = new MockHttpServletResponse();

		ResponseEntity<?> r = controller.refresh("ck-1", null, res);

		assertEquals(200, r.getStatusCode().value());
		LoginResponse body = (LoginResponse) r.getBody();
		assertNotNull(body);
		assertEquals("acc", body.accessToken());
		assertNull(body.refreshToken(), "웹: refresh는 바디로 비노출(HttpOnly 쿠키만)");
		assertTrue(body.refreshTokenCookieSet());
		String setCookie = res.getHeader("Set-Cookie");
		assertTrue(setCookie != null && setCookie.contains("refresh_token=ck-2"), "웹: 회전 토큰을 쿠키로");
	}

	@Test
	void bodyTakesPrecedenceWhenBothPresent() {
		when(store.rotate("from-body")).thenReturn(Optional.of(new RefreshTokenStore.Rotated(7L, "rotated")));
		MockHttpServletResponse res = new MockHttpServletResponse();

		ResponseEntity<?> r = controller.refresh("from-cookie", new RefreshRequest("from-body"), res);

		assertEquals(200, r.getStatusCode().value());
		assertEquals("rotated", ((LoginResponse) r.getBody()).refreshToken());
		assertNull(res.getHeader("Set-Cookie"), "바디 경로이므로 쿠키 미설정");
	}

	@Test
	void noTokenAnywhereIs401() {
		ResponseEntity<?> r = controller.refresh(null, null, new MockHttpServletResponse());
		assertEquals(401, r.getStatusCode().value());
	}

	@Test
	void blankBodyTokenFallsBackThenInvalidIs401() {
		when(store.rotate("ck-x")).thenReturn(Optional.empty());
		ResponseEntity<?> r = controller.refresh("ck-x", new RefreshRequest("  "), new MockHttpServletResponse());
		assertEquals(401, r.getStatusCode().value(), "빈 바디 토큰은 쿠키로 폴백, 쿠키도 무효면 401");
	}
}
