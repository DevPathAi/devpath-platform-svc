package ai.devpath.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import ai.devpath.platform.user.UserOauthIdentityRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class OAuth2LoginSuccessHandlerTest {

	@Autowired OAuth2LoginSuccessHandler handler;
	@Autowired UserOauthIdentityRepository identities;

	@MockitoBean OAuth2AuthorizedClientService authorizedClientService;

	@Test
	void onSuccessUpsertsUserSetsRefreshCookieAndRedirects() throws Exception {
		long uniqueId = 99001 + (System.nanoTime() % 100000);
		String uniqueEmail = "octo-" + System.nanoTime() + "@example.com";
		Map<String, Object> attrs = Map.of(
				"id", (int) uniqueId,
				"login", "octocat", "name", "Octo Cat", "email", uniqueEmail);
		var principal = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), attrs, "id");
		var auth = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "github");

		// P0-2: stub OAuth2AuthorizedClientService to return a client with access token
		ClientRegistration clientReg = ClientRegistration.withRegistrationId("github")
				.clientId("test-client")
				.clientSecret("test-secret")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
				.authorizationUri("https://github.com/login/oauth/authorize")
				.tokenUri("https://github.com/login/oauth/access_token")
				.userInfoUri("https://api.github.com/user")
				.userNameAttributeName("id")
				.build();
		OAuth2AccessToken accessToken = new OAuth2AccessToken(
				OAuth2AccessToken.TokenType.BEARER, "gho_test_access_token",
				Instant.now(), Instant.now().plusSeconds(3600));
		OAuth2AuthorizedClient authorizedClient = new OAuth2AuthorizedClient(clientReg, principal.getName(), accessToken);

		when(authorizedClientService.loadAuthorizedClient(eq("github"), any()))
				.thenReturn(authorizedClient);

		HttpServletRequest req = new MockHttpServletRequest();
		MockHttpServletResponse res = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(req, res, auth);

		assertEquals(302, res.getStatus());
		assertNotNull(res.getRedirectedUrl());
		assertTrue(res.getRedirectedUrl().endsWith("/auth/callback"), "redirect must end with /auth/callback");
		String setCookie = res.getHeader("Set-Cookie");
		assertNotNull(setCookie, "refresh 쿠키 설정");
		assertTrue(setCookie.contains("refresh_token="));
		assertTrue(setCookie.contains("HttpOnly"));

		// P0-2: verify access_token_encrypted was stored (not null)
		String providerUserId = String.valueOf((int) uniqueId);
		var identity = identities.findByProviderAndProviderUserId("GITHUB", providerUserId);
		assertTrue(identity.isPresent(), "identity 행이 존재해야 함");
		assertNotNull(identity.get().getAccessTokenEncrypted(), "provider access token이 암호화 저장돼야 함");
	}
}
