package ai.devpath.platform.support.dto;

import java.util.List;

/**
 * GET /admin/support-requests 응답 봉투.
 * 프론트 dp_core Page.fromJson 계약: { data, nextCursor, limit }.
 * Map.of()는 null 값을 허용하지 않으므로 전용 record 를 쓴다(AdminUsersPage 와 동일).
 */
public record AdminSupportPage(List<AdminSupportRow> data, String nextCursor, int limit) {}
