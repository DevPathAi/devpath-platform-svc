package ai.devpath.platform.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 로그인/refresh 응답.
 * <ul>
 *   <li>웹(쿠키): {@code refreshToken=null} → 바디 비노출(HttpOnly 쿠키만), {@code refreshTokenCookieSet=true}.</li>
 *   <li>모바일(토큰-바디): {@code refreshToken=}회전된 신규 토큰, {@code refreshTokenCookieSet=false}.</li>
 * </ul>
 * {@code @JsonInclude(NON_NULL)}로 웹 응답에는 {@code refresh_token} 키가 나타나지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
		@JsonProperty("access_token") String accessToken,
		@JsonProperty("refresh_token") String refreshToken,
		@JsonProperty("refresh_token_cookie_set") boolean refreshTokenCookieSet,
		UserSummary user) {}
