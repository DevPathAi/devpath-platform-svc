package ai.devpath.platform.consent;

import ai.devpath.platform.consent.dto.ConsentStatusView;
import ai.devpath.platform.consent.dto.ConsentSubmitRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/consents")
public class ConsentController {

	private final ConsentService service;

	public ConsentController(ConsentService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<Void> submit(@AuthenticationPrincipal Jwt jwt, @RequestBody ConsentSubmitRequest body) {
		long userId = Long.parseLong(jwt.getSubject());
		service.submit(userId, body);
		return ResponseEntity.ok().build();
	}

	@GetMapping("/me")
	public ResponseEntity<ConsentStatusView> me(@AuthenticationPrincipal Jwt jwt) {
		long userId = Long.parseLong(jwt.getSubject());
		return ResponseEntity.ok(service.statusOf(userId));
	}

	@PostMapping("/{type}/revoke")
	public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable ConsentType type) {
		long userId = Long.parseLong(jwt.getSubject());
		service.revoke(userId, type);
		return ResponseEntity.ok().build();
	}
}
