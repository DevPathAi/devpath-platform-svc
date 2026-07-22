package ai.devpath.platform.ads;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdController {

  private final AdServeService serve;

  public AdController(AdServeService serve) {
    this.serve = serve;
  }

  /** GET /ads?slot=DASHBOARD_TOP — 적격 광고 1개(200) 또는 없음(204). */
  @GetMapping("/ads")
  public ResponseEntity<AdView> ad(@AuthenticationPrincipal Jwt jwt, @RequestParam String slot) {
    return serve.serve(slot, Long.parseLong(jwt.getSubject()))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }
}
