package ai.devpath.platform.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LoginResponse(
		@JsonProperty("access_token") String accessToken,
		@JsonProperty("refresh_token_cookie_set") boolean refreshTokenCookieSet,
		UserSummary user) {}
