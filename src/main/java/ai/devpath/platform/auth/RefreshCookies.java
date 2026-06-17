package ai.devpath.platform.auth;

import ai.devpath.platform.config.AuthProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the Set-Cookie header value for the refresh token cookie.
 * Cookie name: refresh_token, HttpOnly, SameSite from AuthProperties.
 */
@Component
public class RefreshCookies {

	static final String COOKIE_NAME = "refresh_token";

	private final AuthProperties props;

	public RefreshCookies(AuthProperties props) {
		this.props = props;
	}

	public ResponseCookie create(String tokenValue) {
		ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(COOKIE_NAME, tokenValue)
				.httpOnly(true)
				.path("/")
				.maxAge(props.getRefreshTtl().toSeconds())
				.sameSite(props.getCookieSameSite())
				.secure(props.isCookieSecure());

		String domain = props.getCookieDomain();
		if (domain != null && !domain.isBlank()) {
			builder.domain(domain);
		}

		return builder.build();
	}

	public ResponseCookie clear() {
		ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(COOKIE_NAME, "")
				.httpOnly(true).secure(props.isCookieSecure()).path("/").maxAge(0).sameSite(props.getCookieSameSite());
		if (props.getCookieDomain() != null && !props.getCookieDomain().isBlank()) b.domain(props.getCookieDomain());
		return b.build();
	}
}
