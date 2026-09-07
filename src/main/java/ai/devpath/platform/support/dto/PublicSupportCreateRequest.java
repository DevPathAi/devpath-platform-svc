package ai.devpath.platform.support.dto;

/** Home의 공개 문의·오류 신고. IP와 Turnstile token은 검증에만 쓰고 저장하지 않는다. */
public record PublicSupportCreateRequest(
    String type,
    String email,
    String title,
    String body,
    boolean privacyConsent,
    String turnstileToken) {}
