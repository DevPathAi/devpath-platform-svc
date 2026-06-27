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
 * 순수 단위테스트(Spring 컨텍스트 불요). 모바일 식별을 위해 authorize 요청에
 * {@code ?client_type=mobile}이 오면 state 끝에 마커를 덧붙이는지 검증한다.
 */
class MobileAwareAuthorizationRequestResolverTest {

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
	void mobileRequestAppendsStateMarker() {
		OAuth2AuthorizationRequestResolver delegate = mock(OAuth2AuthorizationRequestResolver.class);
		var resolver = new MobileAwareAuthorizationRequestResolver(delegate);
		MockHttpServletRequest req = new MockHttpServletRequest();
		req.setParameter("client_type", "mobile");
		when(delegate.resolve(req)).thenReturn(base());

		assertEquals("STATE123" + MobileAwareAuthorizationRequestResolver.MOBILE_STATE_SUFFIX,
				resolver.resolve(req).getState());
	}

	@Test
	void mobileMarkerAppliedOnRegistrationIdOverloadToo() {
		OAuth2AuthorizationRequestResolver delegate = mock(OAuth2AuthorizationRequestResolver.class);
		var resolver = new MobileAwareAuthorizationRequestResolver(delegate);
		MockHttpServletRequest req = new MockHttpServletRequest();
		req.setParameter("client_type", "mobile");
		when(delegate.resolve(req, "github")).thenReturn(base());

		assertEquals("STATE123" + MobileAwareAuthorizationRequestResolver.MOBILE_STATE_SUFFIX,
				resolver.resolve(req, "github").getState());
	}

	/**
	 * 실 {@link DefaultOAuth2AuthorizationRequestResolver}와 결합했을 때, 마커가 단순
	 * {@code state} 필드뿐 아니라 GitHub로 실제 전송되는 {@code authorizationRequestUri}의
	 * state 쿼리에도 반영되는지 검증한다(미반영 시 콜백에서 state 불일치 → 로그인 실패).
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

		OAuth2AuthorizationRequest resolved = resolver.resolve(req);
		assertTrue(resolved.getState().endsWith(MobileAwareAuthorizationRequestResolver.MOBILE_STATE_SUFFIX));
		assertTrue(resolved.getAuthorizationRequestUri().contains("state="),
				"authorizationRequestUri에 state 쿼리가 있어야 함");
		assertTrue(resolved.getAuthorizationRequestUri().contains(MobileAwareAuthorizationRequestResolver.MOBILE_STATE_SUFFIX),
				"실제 전송 URI의 state에도 마커가 반영돼야 함: " + resolved.getAuthorizationRequestUri());
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
