package ai.devpath.platform.support;

import ai.devpath.platform.support.dto.SupportCreateRequest;
import ai.devpath.platform.support.dto.SupportCreatedView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 접수. 관리자 조회·전이는 {@link AdminSupportController}.
 *
 * <p>SecurityConfig 의 {@code anyRequest().authenticated()} 로 보호된다 — /support/** 는
 * permitAll 목록에 없다. reporterId 는 요청 본문이 아니라 <b>JWT sub</b>에서 취한다.
 */
@RestController
@RequestMapping("/support")
public class SupportController {

  private final SupportService service;

  public SupportController(SupportService service) {
    this.service = service;
  }

  @PostMapping("/requests")
  public ResponseEntity<SupportCreatedView> create(@AuthenticationPrincipal Jwt jwt,
      @RequestBody SupportCreateRequest req) {
    long reporterId = Long.parseLong(jwt.getSubject());
    SupportRequest saved = service.create(reporterId, req);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new SupportCreatedView(saved.getId() == null ? 0L : saved.getId()));
  }
}
