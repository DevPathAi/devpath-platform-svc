package ai.devpath.platform.support.dto;

import java.util.List;

/** 관리자 상세 = 목록 필드 전체 + 본문 + 수집 컨텍스트 + 실패 목록 + 처리 정보. */
public record AdminSupportDetail(
    long id,
    String type,
    String title,
    String body,
    String status,
    String pagePath,
    String appVersion,
    String userAgent,
    String viewport,
    String traceId,
    String errorCode,
    String occurredAt,
    Long reporterId,
    String adminNote,
    Long handledBy,
    String handledAt,
    String createdAt,
    List<SupportFailureView> failures) {}
