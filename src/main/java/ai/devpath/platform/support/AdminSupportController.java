package ai.devpath.platform.support;

import ai.devpath.platform.support.dto.AdminSupportDetail;
import ai.devpath.platform.support.dto.AdminSupportPage;
import ai.devpath.platform.support.dto.StatusUpdateRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 제보 처리.
 *
 * <p>경로가 {@code /admin/**} 라 SecurityConfig 의 {@code hasRole("ADMIN")} 로 이미 보호된다.
 * ③ 커뮤니티 신고가 게이트웨이의 {@code /admin/**} 선점 때문에
 * {@code /community/admin/...} 로 우회해야 했던 것과 달리, ④는 소유가 platform-svc 라
 * 그 선점이 오히려 유리하게 작용한다 — 게이트웨이 추가 작업이 없다.
 */
@RestController
@RequestMapping("/admin/support-requests")
public class AdminSupportController {

  private final SupportService service;

  public AdminSupportController(SupportService service) {
    this.service = service;
  }

  @GetMapping
  public AdminSupportPage list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false, defaultValue = "20") int limit) {
    return service.list(status, type, cursor, limit);
  }

  @GetMapping("/{id}")
  public AdminSupportDetail detail(@PathVariable long id) {
    return service.detail(id);
  }

  @PostMapping("/{id}/status")
  public AdminSupportDetail updateStatus(@AuthenticationPrincipal Jwt jwt,
      @PathVariable long id, @RequestBody StatusUpdateRequest req) {
    long adminId = Long.parseLong(jwt.getSubject());
    return service.updateStatus(id, adminId, req.status(), req.adminNote());
  }
}
