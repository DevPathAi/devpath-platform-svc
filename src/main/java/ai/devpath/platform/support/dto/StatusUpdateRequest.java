package ai.devpath.platform.support.dto;

/** adminNote 는 선택. 주어지면 덮어쓴다(누적 이력이 아니다). */
public record StatusUpdateRequest(String status, String adminNote) {}
