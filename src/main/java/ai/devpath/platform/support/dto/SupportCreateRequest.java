package ai.devpath.platform.support.dto;

import java.util.List;

/**
 * 접수 요청. 날짜는 <b>String ISO-8601</b>로 받는다 — 서비스에서 {@code Instant.parse} 한다.
 * (jsr310 모듈 의존 없이 계약을 단순하게 유지하기 위함. 파싱 실패는 null 로 흡수한다.)
 */
public record SupportCreateRequest(String type, String title, String body, Context context) {

  public record Context(
      String pagePath,
      String appVersion,
      String userAgent,
      String viewport,
      String traceId,
      String errorCode,
      String occurredAt,
      List<Failure> failures) {}

  public record Failure(
      String method,
      String path,
      Integer statusCode,
      String errorCode,
      String traceId,
      String message,
      String occurredAt) {}
}
