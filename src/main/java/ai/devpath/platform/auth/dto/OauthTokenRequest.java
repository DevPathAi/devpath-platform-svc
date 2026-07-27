package ai.devpath.platform.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 모바일 PKCE 토큰 교환 요청 바디({@code POST /auth/oauth/token}).
 * 딥링크로 받은 일회용 {@code code}와 앱이 보관한 {@code code_verifier}를 제출한다.
 */
public record OauthTokenRequest(
		@JsonProperty("code") String code,
		@JsonProperty("code_verifier") String codeVerifier) {}
