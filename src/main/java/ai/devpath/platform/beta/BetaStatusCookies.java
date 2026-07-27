package ai.devpath.platform.beta;

import ai.devpath.platform.config.AuthProperties;
import ai.devpath.platform.config.BetaProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * beta_status 쿠키 빌더. 쿠키 정책(domain/SameSite/secure)은 refresh 쿠키와 동일하게
 * AuthProperties에서 취하되, 수명은 BetaProperties.statusTtl(기본 30분)을 쓴다.
 */
@Component
public class BetaStatusCookies {

    static final String COOKIE_NAME = "beta_status";

    private final AuthProperties auth;
    private final BetaProperties beta;

    public BetaStatusCookies(AuthProperties auth, BetaProperties beta) {
        this.auth = auth;
        this.beta = beta;
    }

    public ResponseCookie create(String tokenValue) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(COOKIE_NAME, tokenValue)
                .httpOnly(true)
                .path("/")
                .maxAge(beta.getStatusTtl().toSeconds())
                .sameSite(auth.getCookieSameSite())
                .secure(auth.isCookieSecure());
        String domain = auth.getCookieDomain();
        if (domain != null && !domain.isBlank()) {
            b.domain(domain);
        }
        return b.build();
    }
}
