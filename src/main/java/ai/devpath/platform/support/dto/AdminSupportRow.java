package ai.devpath.platform.support.dto;

/** 관리자 목록 행. 날짜는 String ISO-8601. */
public record AdminSupportRow(
    long id,
    String type,
    String title,
    String status,
    String pagePath,
    Long reporterId,
    String source,
    String contactEmail,
    long failureCount,
    String createdAt) {}
