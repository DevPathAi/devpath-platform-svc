package ai.devpath.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.platform.auth.refresh.RefreshTokenStore;
import ai.devpath.platform.config.AuthProperties;
import ai.devpath.platform.mentor.MentorAccessService;
import ai.devpath.platform.release.ReleaseControlService;
import ai.devpath.platform.user.User;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
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

class OAuth2ReleaseLoginSuccessHandlerTest {
	@Test
	void deterministicReleaseTokenIsNeverPersistedAsAProviderCredential() throws Exception {
		UserRegistrationService registration = mock(UserRegistrationService.class);
		RefreshTokenStore refreshStore = mock(RefreshTokenStore.class);
		RefreshCookies cookies = mock(RefreshCookies.class);
		AuthProperties auth = new AuthProperties();
		auth.setWebUrl("https://app.leva.ai.kr");
		OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
		MentorAccessService mentorAccess = mock(MentorAccessService.class);
		ReleaseControlService releaseControl = mock(ReleaseControlService.class);
		User user = mock(User.class);
		when(user.getId()).thenReturn(17L);
		when(user.getEmail()).thenReturn("release@example.test");
		when(registration.registerOrFindRelease(
			"release@example.test", "Release Fixture")).thenReturn(user);
		when(refreshStore.issue(17L)).thenReturn("refresh-value");
		when(cookies.create("refresh-value")).thenReturn(
			ResponseCookie.from("refresh_token", "refresh-value").httpOnly(true).build());

		Map<String, Object> attributes = Map.of(
			"id", "release@example.test",
			"login", "release-fixture",
			"name", "Release Fixture",
			"email", "release@example.test");
		var principal = new DefaultOAuth2User(
			AuthorityUtils.createAuthorityList("ROLE_USER"), attributes, "id");
		var authentication = new OAuth2AuthenticationToken(
			principal, principal.getAuthorities(), "release");
		ClientRegistration clientRegistration = ClientRegistration.withRegistrationId("release")
			.clientId("release-client")
			.clientSecret("release-secret")
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri("https://api.leva.ai.kr/login/oauth2/code/release")
			.authorizationUri("https://oauth.staging.leva.ai.kr/v1/release/oauth/authorize")
			.tokenUri("http://platform/v1/release/oauth/token")
			.userInfoUri("http://platform/v1/release/oauth/userinfo")
			.userNameAttributeName("id")
			.build();
		OAuth2AccessToken accessToken = new OAuth2AccessToken(
			OAuth2AccessToken.TokenType.BEARER,
			"ephemeral-release-token",
			Instant.now(),
			Instant.now().plusSeconds(300));
		when(authorizedClients.loadAuthorizedClient(eq("release"), any()))
			.thenReturn(new OAuth2AuthorizedClient(
				clientRegistration, principal.getName(), accessToken));

		OAuth2LoginSuccessHandler handler = new OAuth2LoginSuccessHandler(
			registration,
			refreshStore,
			cookies,
			auth,
			authorizedClients,
			mock(AuthCodeStore.class),
			mentorAccess,
			releaseControl);
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-Candidate-Spec-Sha256", "a".repeat(64));
		request.addHeader("X-Release-Run-Key", "R".repeat(43));
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(request, response, authentication);

		verify(registration).registerOrFindRelease(
			"release@example.test", "Release Fixture");
		verify(registration, never()).registerOrFind(any());
		verify(releaseControl).completeLogin("a".repeat(64), "R".repeat(43), user);
		assertThat(response.getRedirectedUrl()).isEqualTo("https://app.leva.ai.kr/auth/callback");
	}
}
