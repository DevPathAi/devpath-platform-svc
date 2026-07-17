package ai.devpath.platform.beta;

import ai.devpath.platform.beta.dto.AdminUserRow;
import ai.devpath.platform.beta.dto.AdminUsersPage;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 사용자 관리 API.
 * /admin/** 는 SecurityConfig에서 hasRole("ADMIN")으로 이미 보호된다(B6).
 *
 * 엔드포인트:
 *   GET  /admin/users?status=&cursor=&limit=   — keyset 페이지네이션 목록
 *   POST /admin/users/{id}/approve             — 사용자 승인 (204)
 *   POST /admin/allowlist                      — 이메일 사전승인 (204)
 */
@RestController
@RequestMapping("/admin")
public class AdminUserController {

    private final UserRepository users;
    private final AdminBetaService betaService;

    public AdminUserController(UserRepository users, AdminBetaService betaService) {
        this.users = users;
        this.betaService = betaService;
    }

    /**
     * 사용자 목록 (keyset 페이지네이션).
     *
     * - status 파라미터가 있으면 해당 상태만 필터링.
     * - cursor(id 문자열)가 있으면 해당 id 초과 행만 반환.
     * - limit 기본 20, 최대 100.
     * - nextCursor: 마지막 행의 id(String). 결과가 limit 미만이면 null.
     *
     * 응답 형태(dp_core Page.fromJson 계약):
     *   { "data": [...], "nextCursor": <string|null>, "limit": <int> }
     */
    @GetMapping("/users")
    public AdminUsersPage list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit) {

        int size = Math.min(Math.max(limit, 1), 100);
        long after = (cursor == null || cursor.isBlank()) ? 0L : Long.parseLong(cursor);
        var pageable = PageRequest.of(0, size);

        List<User> rows = (status == null || status.isBlank())
                ? users.findByIdGreaterThanOrderByIdAsc(after, pageable)
                : users.findByStatusAndIdGreaterThanOrderByIdAsc(status, after, pageable);

        // nextCursor: 꽉 찬 페이지일 때만 마지막 행의 id. 그렇지 않으면 null(다음 페이지 없음).
        String nextCursor = (rows.size() == size)
                ? String.valueOf(rows.get(rows.size() - 1).getId())
                : null;

        List<AdminUserRow> data = rows.stream().map(AdminUserRow::of).toList();
        return new AdminUsersPage(data, nextCursor, size);
    }

    /**
     * 사용자 승인 — 204 No Content.
     * AdminBetaService.approveUser가 status=ACTIVE 전환 + 아웃박스 이벤트를 처리한다.
     */
    @PostMapping("/users/{id}/approve")
    public ResponseEntity<Void> approve(@PathVariable long id) {
        betaService.approveUser(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 이메일 사전승인 — 204 No Content.
     * 이미 가입한 사용자라면 즉시 ACTIVE로 전환한다.
     */
    @PostMapping("/allowlist")
    public ResponseEntity<Void> allowlist(@RequestBody Map<String, String> body) {
        betaService.preApprove(body.get("email"), "admin-api");
        return ResponseEntity.noContent().build();
    }
}
