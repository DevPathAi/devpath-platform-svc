package ai.devpath.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * 순수 단위테스트(Spring 컨텍스트 불요). 모바일(PKCE) 식별을 위해 authorize 요청에
 * {@code client_type=mobile} + {@code code_challenge}가 오면 state에 마커+challenge를
 * 붙이는지 검증한다. state 형태: {@code <csrf>.mobile.<challenge>}.
 */
class MobileAwareAuthorizationRequestResolverTest {

	private static final String CC = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"; // 예시 code_challenge(base64url)

	private OAuth2AuthorizationRequest base() {
		return OAuth2AuthorizationRequest.authorizationCode()
				.authorizationUri("https://github.com/login/oauth/authorize")
				.clientId("c")
				.redirectUri("https://app/login/oauth2/code/github")
				.state("STATE123")
				.build();
	}

	@Test
	void nonMobileRequestLeavesStateUntouched() {
		OAuth2AuthorizationRequestResolver delegate = mock(OAuth2AuthorizationRequestResolver.class);
		var resolver = new MobileAwareAuthorizationRequestResolver(delegate);
		MockHttpServletRequest req = new MockHttpServletRequest();
		when(delegate.resolve(req)).thenReturn(base());

		assertEquals("STATE123", resolver.resolve(req).getState());
	}

	@Test
	void mobileWithChallengeAppendsMarkerAndChallenge() {
		OAuth2AuthorizationRequestResolver delegate = mock(OAuth2AuthorizationRequestResolver.class);
		var resolver = new MobileAwareAuthorizationRequestResolver(delegate);
		MockHttpServletRequest req = new MockHttpServletRequest();
		req.setParameter("client_type", "mobile");
		req.setParameter("code_challenge", CC);
		when(delegate.resolve(req)).thenReturn(base());

		assertEquals("STATE123" + MobileAwareAuthorizationRequestResolver.MOBILE_STATE_MARKER + CC,
				resolver.resolve(req).getState());
	}

	@Test
	void mobileWithoutChallengeFallsBackToWeb() {
		OAuth2AuthorizationRequestResolver delegate = mock(OAuth2AuthorizationRequestResolver.class);
		var resolver = new MobileAwareAuthorizationRequestResolver(delegate);
		MockHttpServletRequest req = new MockHttpServletRequest();
		req.setParameter("client_type", "mobile"); // challenge 누락 → 마커 미부여
		when(delegate.resolve(req)).thenReturn(base());

		assertEquals("STATE123", resolver.resolve(req).getState());
	}

	@Test
	void mobileMarkerAppliedOnRegistrationIdOverloadToo() {
		OAuth2AuthorizationRequestResolver delegate = mock(OAuth2AuthorizationRequestResolver.class);
		var resolver = new MobileAwareAuthorizationRequestResolver(delegate);
		MockHttpServletRequest req = new MockHttpServletRequest();
		req.setParameter("client_type", "mobile");
		req.setParameter("code_challenge", CC);
		when(delegate.resolve(req, "github")).thenReturn(base());

		assertEquals("STATE123" + MobileAwareAuthorizationRequestResolver.MOBILE_STATE_MARKER + CC,
				resolver.resolve(req, "github").getState());
	}

	/**
	 * 실 {@link DefaultOAuth2AuthorizationRequestResolver}와 결합했을 때, 마커+challenge가
	 * 단순 {@code state} 필드뿐 아니라 실제 전송되는 {@code authorizationRequestUri}의 state
	 * 쿼리에도 반영되는지 검증한다(미반영 시 콜백에서 state 불일치 → 로그인 실패).
	 */
	@Test
	void mobileMarkerReflectedInAuthorizationRequestUri() {
		ClientRegistration github = ClientRegistration.withRegistrationId("github")
				.clientId("test-client")
				.clientSecret("test-secret")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
				.authorizationUri("https://github.com/login/oauth/authorize")
				.tokenUri("https://github.com/login/oauth/access_token")
				.userInfoUri("https://api.github.com/user")
				.userNameAttributeName("id")
				.scope("read:user")
				.build();
		var realDelegate = new DefaultOAuth2AuthorizationRequestResolver(
				new InMemoryClientRegistrationRepository(github), "/oauth2/authorization");
		var resolver = new MobileAwareAuthorizationRequestResolver(realDelegate);

		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
		req.setServletPath("/oauth2/authorization/github");
		req.setParameter("client_type", "mobile");
		req.setParameter("code_challenge", CC);

		OAuth2AuthorizationRequest resolved = resolver.resolve(req);
		String marker = MobileAwareAuthorizationRequestResolver.MOBILE_STATE_MARKER;
		assertTrue(resolved.getState().contains(marker + CC));
		assertTrue(resolved.getAuthorizationRequestUri().contains(marker + CC),
				"실제 전송 URI의 state에도 마커+challenge가 반영돼야 함: " + resolved.getAuthorizationRequestUri());
	}

	@Test
	void nullDelegateResultPassesThrough() {
		OAuth2AuthorizationRequestResolver delegate = mock(OAuth2AuthorizationRequestResolver.class);
		var resolver = new MobileAwareAuthorizationRequestResolver(delegate);
		MockHttpServletRequest req = new MockHttpServletRequest();
		when(delegate.resolve(req)).thenReturn(null);

		assertNull(resolver.resolve(req));
	}
}
