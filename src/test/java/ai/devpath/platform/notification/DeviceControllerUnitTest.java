package ai.devpath.platform.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

/** 순수 단위테스트(Spring 컨텍스트·DB 불요). 디바이스 토큰 등록/해제 upsert 로직. */
class DeviceControllerUnitTest {

	private DeviceTokenRepository repo;
	private DeviceController controller;
	private final Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("7").build();

	@BeforeEach
	void setUp() {
		repo = mock(DeviceTokenRepository.class);
		controller = new DeviceController(repo);
	}

	@Test
	void registerNewTokenSavesWithUserAndReturns204() {
		when(repo.findByToken("tok")).thenReturn(Optional.empty());

		ResponseEntity<Void> r = controller.register(jwt, new DeviceRegistrationRequest("tok", "ANDROID"));

		assertEquals(204, r.getStatusCode().value());
		ArgumentCaptor<DeviceToken> cap = ArgumentCaptor.forClass(DeviceToken.class);
		verify(repo).save(cap.capture());
		assertEquals(7L, cap.getValue().getUserId());
		assertEquals("tok", cap.getValue().getToken());
		assertEquals("ANDROID", cap.getValue().getPlatform());
		assertNotNull(cap.getValue().getUpdatedAt());
	}

	@Test
	void registerExistingTokenUpsertsSameRow() {
		DeviceToken existing = new DeviceToken();
		existing.setUserId(1L);
		existing.setToken("tok");
		existing.setPlatform("IOS");
		when(repo.findByToken("tok")).thenReturn(Optional.of(existing));

		controller.register(jwt, new DeviceRegistrationRequest("tok", "ANDROID"));

		verify(repo).save(existing); // 동일 행 재사용(새 행 생성 안 함)
		assertEquals(7L, existing.getUserId());
		assertEquals("ANDROID", existing.getPlatform());
	}

	@Test
	void missingFieldsBadRequest() {
		assertEquals(400, controller.register(jwt, null).getStatusCode().value());
		assertEquals(400, controller.register(jwt, new DeviceRegistrationRequest(null, "ANDROID")).getStatusCode().value());
		assertEquals(400, controller.register(jwt, new DeviceRegistrationRequest("tok", " ")).getStatusCode().value());
		verify(repo, never()).save(any());
	}

	@Test
	void unregisterDeletesOwnTokenIfPresent() {
		DeviceToken existing = new DeviceToken();
		existing.setUserId(7L); // 호출자(jwt sub=7) 소유
		when(repo.findByToken("tok")).thenReturn(Optional.of(existing));

		ResponseEntity<Void> r = controller.unregister(jwt, new DeviceRegistrationRequest("tok", null));

		assertEquals(204, r.getStatusCode().value());
		verify(repo).delete(existing);
	}

	@Test
	void unregisterDoesNotDeleteOthersToken() {
		DeviceToken othersToken = new DeviceToken();
		othersToken.setUserId(99L); // 다른 사용자 소유 → 삭제 금지(IDOR 방지)
		when(repo.findByToken("tok")).thenReturn(Optional.of(othersToken));

		ResponseEntity<Void> r = controller.unregister(jwt, new DeviceRegistrationRequest("tok", null));

		assertEquals(204, r.getStatusCode().value());
		verify(repo, never()).delete(any());
	}

	@Test
	void unregisterMissingTokenIsNoOp204() {
		ResponseEntity<Void> r = controller.unregister(jwt, null);
		assertEquals(204, r.getStatusCode().value());
		verify(repo, never()).delete(any());
	}
}
