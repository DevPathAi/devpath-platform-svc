package ai.devpath.platform.beta.dto;

import ai.devpath.platform.user.User;

/**
 * Admin 사용자 목록 응답 행 DTO.
 * id는 String으로 직렬화(프론트 dp_core Page.fromJson 계약).
 */
public record AdminUserRow(String id, String nickname, String email, String role, String status) {

    public static AdminUserRow of(User u) {
        return new AdminUserRow(
                String.valueOf(u.getId()),
                u.getNickname(),
                u.getEmail(),
                u.getRole(),
                u.getStatus());
    }
}
