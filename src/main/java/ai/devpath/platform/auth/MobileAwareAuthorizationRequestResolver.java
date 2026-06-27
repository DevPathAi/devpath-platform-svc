package ai.devpath.platform.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * 모바일 OAuth 플로우 식별기.
 *
 * <p>네이티브 앱이 {@code /oauth2/authorization/github?client_type=mobile} 로 로그인을
 * 시작하면, GitHub 왕복 후 콜백(success handler)에서도 모바일임을 알아야 한다. OAuth 규격상
 * {@code state}는 그대로 round-trip 되므로, 위임 resolver가 만든 랜덤 state(=CSRF 보호) 끝에
 * 마커를 덧붙여 그 채널로 모바일 여부를 전달한다.
 *
 * <p>마커는 success handler({@link OAuth2LoginSuccessHandler})가 {@code state} 파라미터의
 * suffix로 읽는다.
 */
public class MobileAwareAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

	static final String CLIENT_TYPE_PARAM = "client_type";
	static final String MOBILE = "mobile";
	// 구분자 '.'는 base64url state 알파벳(A-Za-z0-9-_)에 없으므로, 웹의 랜덤 state가
	// 우연히 이 suffix로 끝나 모바일로 오분류될 가능성이 없다(충돌-불가). '.'는 URL
	// unreserved라 인코딩되지 않아 GitHub 왕복에서 그대로 보존된다.
	static final String MOBILE_STATE_SUFFIX = ".mobile";

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
		if (!MOBILE.equals(request.getParameter(CLIENT_TYPE_PARAM))) {
			return req;
		}
		return OAuth2AuthorizationRequest.from(req)
				.state(req.getState() + MOBILE_STATE_SUFFIX)
				.build();
	}
}
