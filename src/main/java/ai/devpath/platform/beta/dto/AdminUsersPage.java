package ai.devpath.platform.beta.dto;

import java.util.List;

/**
 * GET /admin/users 응답 봉투.
 *
 * Map.of()는 null 값을 허용하지 않으므로 전용 record를 사용한다.
 * Jackson이 nextCursor=null을 JSON null로 직렬화한다.
 *
 * 프론트 dp_core Page.fromJson 계약: { data, nextCursor, limit }
 */
public record AdminUsersPage(List<AdminUserRow> data, String nextCursor, int limit) {
}
