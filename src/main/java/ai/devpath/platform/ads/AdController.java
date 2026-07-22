package ai.devpath.platform.ads;

import ai.devpath.platform.ads.dto.AdEventRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdController {

  private final AdServeService serve;
  private final AdEventService eventService;

  public AdController(AdServeService serve, AdEventService eventService) {
    this.serve = serve;
    this.eventService = eventService;
  }

  /** GET /ads?slot=DASHBOARD_TOP — 적격 광고 1개(200) 또는 없음(204). */
  @GetMapping("/ads")
  public ResponseEntity<AdView> ad(@AuthenticationPrincipal Jwt jwt, @RequestParam String slot) {
    return serve.serve(slot, Long.parseLong(jwt.getSubject()))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PostMapping("/ads/{id}/events")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void event(
      @PathVariable long id,
      @RequestBody AdEventRequest body) {
    try {
      eventService.record(id, body.type());
    } catch (DataIntegrityViolationException race) {
      // existsById 통과 후 upsert 직전 광고가 동시 삭제된 레이스(FK 위반).
      // 이벤트는 유실 허용(spec)이므로 흡수하고 202를 유지한다.
      // 트랜잭션 경계(record) 바깥에서 잡아야 rollback-only 트랩을 피한다.
    }
  }
}
