package ai.devpath.platform.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * 모바일 OAuth(PKCE) 플로우 식별기.
 *
 * <p>네이티브 앱이 {@code /oauth2/authorization/github?client_type=mobile&code_challenge=<cc>
 * &code_challenge_method=S256} 로 로그인을 시작하면, GitHub 왕복 후 콜백(success handler)에서도
 * 모바일임 + {@code code_challenge}를 알아야 한다. OAuth 규격상 {@code state}는 그대로
 * round-trip 되므로, 위임 resolver가 만든 랜덤 state(=CSRF 보호) 뒤에 마커와 challenge를 붙여
 * 그 채널로 전달한다. challenge는 PKCE 규격상 공개값이라 노출돼도 안전하다.
 *
 * <p>state 형태: {@code <csrf>.mobile.<code_challenge>}. 구분자 {@code .}는 base64url
 * 알파벳(A-Za-z0-9-_)에 없어 csrf/challenge와 충돌하지 않으며, URL unreserved라 인코딩 없이
 * 보존된다. success handler({@link OAuth2LoginSuccessHandler})가 마커로 모바일을 식별하고
 * challenge를 분리한다.
 */
public class MobileAwareAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

	static final String CLIENT_TYPE_PARAM = "client_type";
	static final String CODE_CHALLENGE_PARAM = "code_challenge";
	static final String MOBILE = "mobile";
	static final String MOBILE_STATE_MARKER = ".mobile.";

	private final OAuth2AuthorizationRequestResolver delegate;

	public MobileAwareAuthorizationRequestResolver(OAuth2AuthorizationRequestResolver delegate) {
		this.delegate = delegate;
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
		return decorate(delegate.resolve(request), request);
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
		return decorate(delegate.resolve(request, clientRegistrationId), request);
	}

	private OAuth2AuthorizationRequest decorate(OAuth2AuthorizationRequest req, HttpServletRequest request) {
		if (req == null) {
			return null;
		}
		String challenge = request.getParameter(CODE_CHALLENGE_PARAM);
		// 모바일 + PKCE challenge가 모두 있을 때만 마커를 부여한다(없으면 웹 플로우).
		if (!MOBILE.equals(request.getParameter(CLIENT_TYPE_PARAM)) || challenge == null || challenge.isBlank()) {
			return req;
		}
		return OAuth2AuthorizationRequest.from(req)
				.state(req.getState() + MOBILE_STATE_MARKER + challenge)
				.build();
	}
}
