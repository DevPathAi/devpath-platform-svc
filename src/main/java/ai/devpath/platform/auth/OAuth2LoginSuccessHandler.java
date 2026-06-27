package ai.devpath.platform.auth;

import ai.devpath.platform.config.AuthProperties;
import ai.devpath.platform.auth.jwt.JwtService;
import ai.devpath.platform.auth.refresh.RefreshTokenStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

	private final UserRegistrationService registration;
	private final RefreshTokenStore refreshStore;
	private final RefreshCookies cookies;
	private final AuthProperties props;
	private final OAuth2AuthorizedClientService authorizedClients;
	private final JwtService jwt;

	public OAuth2LoginSuccessHandler(UserRegistrationService registration, RefreshTokenStore refreshStore,
			RefreshCookies cookies, AuthProperties props,
			OAuth2AuthorizedClientService authorizedClients, JwtService jwt) {
		this.registration = registration;
		this.refreshStore = refreshStore;
		this.cookies = cookies;
		this.props = props;
		this.authorizedClients = authorizedClients;
		this.jwt = jwt;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
		String registrationId = token.getAuthorizedClientRegistrationId();
		String provider = registrationId.toUpperCase();
		Map<String, Object> attrs = token.getPrincipal().getAttributes();
		String providerUserId = String.valueOf(attrs.get("id"));
		String nickname = attrs.get("name") != null
				? String.valueOf(attrs.get("name"))
				: String.valueOf(attrs.get("login"));
		String email = attrs.get("email") != null ? String.valueOf(attrs.get("email")) : null;

		// P0-2: capture provider access token via OAuth2AuthorizedClientService → Tink 암호화 저장
		var client = authorizedClients.loadAuthorizedClient(registrationId, token.getName());
		String accessToken = (client != null && client.getAccessToken() != null)
				? client.getAccessToken().getTokenValue() : null;

		var user = registration.registerOrFind(
				new UserRegistrationService.OauthUser(provider, providerUserId, email, nickname, accessToken));

		String refresh = refreshStore.issue(user.getId());

		// 모바일 플로우는 state 마커로 식별(MobileAwareAuthorizationRequestResolver가 부여).
		String state = request.getParameter("state");
		boolean mobile = state != null && state.endsWith(MobileAwareAuthorizationRequestResolver.MOBILE_STATE_SUFFIX);
		if (mobile) {
			// 네이티브: 쿠키를 못 읽으므로 access(즉시 mint)+refresh를 커스텀 스킴 fragment로 전달.
			// fragment(#)는 서버로 전송되지 않아 query보다 토큰 노출 위험이 낮다.
			String access = jwt.mintAccessToken(user.getId(), user.getRole());
			String target = props.getMobileRedirectUri()
					+ "#access_token=" + enc(access)
					+ "&refresh_token=" + enc(refresh);
			response.sendRedirect(target);
			return;
		}

		// 웹: refresh는 HttpOnly 쿠키, 프론트 콜백 페이지로 리다이렉트(access는 후속 /auth/refresh로 수령).
		response.addHeader(HttpHeaders.SET_COOKIE, cookies.create(refresh).toString());
		response.sendRedirect(props.getWebUrl() + "/auth/callback");
	}

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
