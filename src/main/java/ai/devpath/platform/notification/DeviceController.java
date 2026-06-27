package ai.devpath.platform.notification;

import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FCM 디바이스 토큰 등록/해제(하드닝 트랙 C). Bearer 액세스 토큰 필요(SecurityConfig의
 * anyRequest().authenticated()). 모바일은 로그인 후 getToken() 결과를 여기에 등록한다.
 */
@RestController
@RequestMapping("/notifications/devices")
public class DeviceController {

	private final DeviceTokenRepository devices;

	public DeviceController(DeviceTokenRepository devices) {
		this.devices = devices;
	}

	/** 토큰 등록(멱등 upsert: 같은 token이면 user/platform/updated_at 갱신). */
	@PostMapping
	public ResponseEntity<Void> register(@AuthenticationPrincipal Jwt jwt,
			@RequestBody(required = false) DeviceRegistrationRequest body) {
		if (body == null || isBlank(body.token()) || isBlank(body.platform())) {
			return ResponseEntity.badRequest().build();
		}
		long userId = Long.parseLong(jwt.getSubject());
		DeviceToken dt = devices.findByToken(body.token()).orElseGet(DeviceToken::new);
		dt.setUserId(userId);
		dt.setToken(body.token());
		dt.setPlatform(body.platform());
		dt.setUpdatedAt(Instant.now());
		devices.save(dt);
		return ResponseEntity.noContent().build();
	}

	/** 토큰 해제(로그아웃/기기 해지). 없으면 무시(멱등). 본인 소유 토큰만 삭제(IDOR 방지). */
	@DeleteMapping
	public ResponseEntity<Void> unregister(@AuthenticationPrincipal Jwt jwt,
			@RequestBody(required = false) DeviceRegistrationRequest body) {
		if (body != null && !isBlank(body.token())) {
			long userId = Long.parseLong(jwt.getSubject());
			devices.findByToken(body.token())
					.filter(t -> t.getUserId() != null && t.getUserId() == userId)
					.ifPresent(devices::delete);
		}
		return ResponseEntity.noContent().build();
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}
}
