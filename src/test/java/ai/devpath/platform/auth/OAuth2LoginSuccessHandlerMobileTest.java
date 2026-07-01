package ai.devpath.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.devpath.platform.auth.refresh.RefreshTokenStore;
import ai.devpath.platform.config.AuthProperties;
import ai.devpath.platform.user.User;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/**
 * 순수 단위테스트(Spring 컨텍스트·DB·Redis 불요). 협력자를 모킹해 OAuth 성공 후
 * 모바일(일회용 code) / 웹(쿠키) 리다이렉트 분기만 검증한다. DB 영속 검증은 통합테스트
 * {@link OAuth2LoginSuccessHandlerTest}가 담당.
 */
class OAuth2LoginSuccessHandlerMobileTest {

	private static final String ISSUED_REFRESH = "refresh-xyz";
	private static final String ISSUED_CODE = "one-time-code";
	private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

	private UserRegistrationService registration;
	private RefreshTokenStore refreshStore;
	private AuthCodeStore authCodeStore;
	private OAuth2AuthorizedClientService authorizedClients;
	private AuthProperties props;
	private OAuth2LoginSuccessHandler handler;

	@BeforeEach
	void setUp() {
		registration = mock(UserRegistrationService.class);
		refreshStore = mock(RefreshTokenStore.class);
		authCodeStore = mock(AuthCodeStore.class);
		authorizedClients = mock(OAuth2AuthorizedClientService.class);

		props = new AuthProperties();
		props.setWebUrl("https://web.devpath.ai");
		props.setMobileRedirectUri("devpath://callback");

		User user = mock(User.class);
		when(user.getId()).thenReturn(7L);
		when(registration.registerOrFind(any())).thenReturn(user);
		when(refreshStore.issue(7L)).thenReturn(ISSUED_REFRESH);
		when(authCodeStore.issue(7L, CHALLENGE)).thenReturn(ISSUED_CODE);
		when(authorizedClients.loadAuthorizedClient(anyString(), anyString())).thenReturn(null);

		handler = new OAuth2LoginSuccessHandler(
				registration, refreshStore, new RefreshCookies(props), props, authorizedClients, authCodeStore);
	}

	private OAuth2AuthenticationToken githubAuth() {
		Map<String, Object> attrs = Map.of("id", 12345, "login", "octocat", "name", "Octo Cat");
		var principal = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), attrs, "id");
		return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "github");
	}

	@Test
	void webFlowSetsRefreshCookieAndRedirectsToWebCallback() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest();
		MockHttpServletResponse res = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(req, res, githubAuth());

		assertEquals(302, res.getStatus());
		assertTrue(res.getRedirectedUrl().endsWith("/auth/callback"), "웹은 webUrl/auth/callback 리다이렉트");
		String setCookie = res.getHeader("Set-Cookie");
		assertTrue(setCookie != null && setCookie.contains("refresh_token=") && setCookie.contains("HttpOnly"),
				"웹은 refresh HttpOnly 쿠키 설정");
		assertTrue(!res.getRedirectedUrl().contains("token"), "웹 리다이렉트 URL엔 토큰/코드 비노출");
	}

	@Test
	void mobileFlowRedirectsToDeeplinkWithOneTimeCodeAndNoTokensNoCookie() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest();
		// 모바일(PKCE): state = "<csrf>.mobile.<challenge>" (resolver가 부여).
		req.setParameter("state", "csrf-rand" + MobileAwareAuthorizationRequestResolver.MOBILE_STATE_MARKER + CHALLENGE);
		MockHttpServletResponse res = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(req, res, githubAuth());

		String url = res.getRedirectedUrl();
		assertEquals("devpath://callback?code=" + ISSUED_CODE, url, "모바일은 일회용 code만 딥링크로: " + url);
		assertTrue(!url.contains("access_token") && !url.contains("refresh_token"), "토큰은 URL에 없어야 함: " + url);
		assertNull(res.getHeader("Set-Cookie"), "모바일은 쿠키 미설정");
	}
}
