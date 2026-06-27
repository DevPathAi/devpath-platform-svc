package ai.devpath.platform.notification;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 디바이스 토큰 등록/해제 요청 바디. */
public record DeviceRegistrationRequest(
		@JsonProperty("token") String token,
		@JsonProperty("platform") String platform) {}
