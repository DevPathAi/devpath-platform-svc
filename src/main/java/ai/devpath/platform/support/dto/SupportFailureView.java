package ai.devpath.platform.support.dto;

/** 상세의 실패 목록 1행(seq 오름차순). statusCode 는 네트워크 실패면 null. */
public record SupportFailureView(
    short seq,
    String method,
    String path,
    Short statusCode,
    String errorCode,
    String traceId,
    String message,
    String occurredAt) {}
