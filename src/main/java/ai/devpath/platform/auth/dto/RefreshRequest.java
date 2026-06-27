package ai.devpath.platform.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 모바일(토큰-바디) refresh 요청 바디. 네이티브 앱은 쿠키 저장이 불안정하므로
 * secure storage의 refresh 토큰을 바디로 전송한다. 웹(쿠키)은 바디 없이 호출하므로
 * 컨트롤러에서 {@code required=false}로 받는다.
 */
public record RefreshRequest(@JsonProperty("refresh_token") String refreshToken) {}
