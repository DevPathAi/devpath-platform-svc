package ai.devpath.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.devpath.platform.beta.BetaGate;
import ai.devpath.platform.config.AuthProperties;
import ai.devpath.platform.config.BetaProperties;
import ai.devpath.platform.user.User;
import ai.devpath.platform.auth.refresh.RefreshTokenStore;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
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

@ExtendWith(MockitoExtension.class)
class OAuth2LoginSuccessHandlerBetaTest {

    @Mock UserRegistrationService registration;
    @Mock RefreshTokenStore refreshStore;
    @Mock RefreshCookies cookies;
    @Mock AuthProperties props;
    @Mock OAuth2AuthorizedClientService authorizedClients;
    @Mock AuthCodeStore authCodeStore;
    @Mock BetaGate betaGate;
    @Mock BetaProperties betaProps;
    @Mock ai.devpath.platform.beta.BetaStatusTokens betaStatusTokens;
    @Mock ai.devpath.platform.beta.BetaStatusCookies betaStatusCookies;

    OAuth2LoginSuccessHandler handler;
    OAuth2AuthenticationToken authentication;
    User user;

    @BeforeEach
    void setUp() throws Exception {
        handler = new OAuth2LoginSuccessHandler(
                registration, refreshStore, cookies, props, authorizedClients, authCodeStore,
                betaGate, betaProps, betaStatusTokens, betaStatusCookies,
                Mockito.mock(ai.devpath.platform.release.ReleaseControlService.class));

        long uniqueId = 99001L;
        String uniqueEmail = "octo@example.com";
        Map<String, Object> attrs = Map.of(
                "id", (int) uniqueId,
                "login", "octocat", "name", "Octo Cat", "email", uniqueEmail);
        var principal = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), attrs, "id");
        authentication = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "github");

        // Stub OAuth2AuthorizedClientService
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
        OAuth2AuthorizedClient authorizedClient = new OAuth2AuthorizedClient(
                clientReg, principal.getName(), accessToken);
        when(authorizedClients.loadAuthorizedClient(eq("github"), any()))
                .thenReturn(authorizedClient);

        // Stub registerOrFind → user (mock so getId() returns non-null for web flow)
        user = Mockito.mock(User.class);
        // getId() is only invoked in the admitted-user path; use lenient to avoid UnnecessaryStubbingException in unlisted-user test
        lenient().when(user.getId()).thenReturn(42L);
        when(registration.registerOrFind(any())).thenReturn(user);

        // props.getWebUrl() needed for web/pending paths but not admin; use lenient to avoid UnnecessaryStubbingException
        lenient().when(props.getWebUrl()).thenReturn("https://app.example");
    }

    @Test
    void unlistedUser_setsBetaStatusCookie_redirectsToBetaPending() throws Exception {
        when(betaGate.admit(any())).thenReturn(false);
        when(betaProps.getPendingRedirect()).thenReturn("/beta-pending");
        when(betaStatusTokens.issue(anyLong())).thenReturn("st");
        when(betaStatusCookies.create("st")).thenReturn(
                ResponseCookie.from("beta_status", "st").httpOnly(true).path("/").build());

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        // beta_status 쿠키 발급 + /beta-pending 리다이렉트, refresh 미발급.
        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "beta_status 쿠키 설정돼야 함");
        org.junit.jupiter.api.Assertions.assertTrue(setCookie.contains("beta_status"), "beta_status 쿠키명");
        verifyNoInteractions(refreshStore);
        assertEquals("https://app.example/beta-pending", response.getRedirectedUrl());
    }

    @Test
    void admittedUser_followsExistingWebFlow() throws Exception {
        when(betaGate.admit(any())).thenReturn(true);
        when(refreshStore.issue(anyLong())).thenReturn("rt");
        when(cookies.create("rt")).thenReturn(
                ResponseCookie.from("refresh_token", "rt").httpOnly(true).path("/").build());

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        // refresh 쿠키 설정 확인
        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        org.junit.jupiter.api.Assertions.assertNotNull(setCookie, "refresh 쿠키 설정돼야 함");
        assertEquals("https://app.example/auth/callback", response.getRedirectedUrl());
    }

    @Test
    void adminMarkedLogin_setsRefreshCookie_redirectsToAdminCallback() throws Exception {
        when(betaGate.admit(any())).thenReturn(true);
        when(props.getAdminUrl()).thenReturn("https://admin.example");
        when(refreshStore.issue(anyLong())).thenReturn("rt");
        when(cookies.create("rt")).thenReturn(
                org.springframework.http.ResponseCookie.from("refresh_token", "rt").httpOnly(true).path("/").build());
        // request.getParameter("state") → csrf.admin.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("state", "csrf.admin.");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        assertEquals("https://admin.example/auth/callback", response.getRedirectedUrl());
        assertNotNull(response.getHeader(HttpHeaders.SET_COOKIE));
    }
}
