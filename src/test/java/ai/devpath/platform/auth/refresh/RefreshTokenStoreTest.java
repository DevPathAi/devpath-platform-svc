package ai.devpath.platform.auth.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.devpath.platform.config.AuthProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RefreshTokenStoreTest {

	@Autowired StringRedisTemplate redis;

	private RefreshTokenStore store(Duration grace) {
		AuthProperties props = new AuthProperties();
		props.setRefreshTtl(Duration.ofDays(14));
		props.setRefreshRotateGrace(grace);
		return new RefreshTokenStore(redis, props);
	}

	@Test
	void issueValidateRotateRevoke_singleUseWhenGraceDisabled() {
		RefreshTokenStore s = store(Duration.ZERO);
		String t = s.issue(42L);
		assertEquals(42L, s.validate(t).orElseThrow());

		var rotated = s.rotate(t).orElseThrow();
		assertEquals(42L, rotated.userId());
		assertFalse(s.validate(t).isPresent(), "grace=0이면 회전 후 이전 토큰 즉시 무효");
		assertFalse(s.rotate(t).isPresent(), "grace=0이면 이전 토큰 재회전 불가");
		assertEquals(42L, s.validate(rotated.newToken()).orElseThrow());

		s.revoke(rotated.newToken());
		assertFalse(s.validate(rotated.newToken()).isPresent(), "폐기 후 무효");
	}

	@Test
	void rotateWithinGraceAllowsConcurrentReuse() {
		RefreshTokenStore s = store(Duration.ofSeconds(30));
		String t = s.issue(7L);

		var first = s.rotate(t).orElseThrow();
		// 유예창 내 동일(직전) 토큰 재회전 — 동시 refresh/멀티탭 시나리오.
		var second = s.rotate(t).orElseThrow();

		assertEquals(7L, first.userId());
		assertEquals(7L, second.userId());
		assertNotEquals(first.newToken(), second.newToken(), "재사용마다 새 토큰 발급");
		assertNotEquals(t, first.newToken());
		assertEquals(7L, s.validate(first.newToken()).orElseThrow());
		assertEquals(7L, s.validate(second.newToken()).orElseThrow());
	}

	@Test
	void graceTokenStillValidates() {
		RefreshTokenStore s = store(Duration.ofSeconds(30));
		String t = s.issue(8L);
		s.rotate(t).orElseThrow();
		assertEquals(8L, s.validate(t).orElseThrow(), "유예 토큰은 validate에서 유효");
	}

	@Test
	void revokeKillsGraceToken() {
		RefreshTokenStore s = store(Duration.ofSeconds(30));
		String t = s.issue(9L);
		s.rotate(t).orElseThrow();
		s.revoke(t);
		assertFalse(s.rotate(t).isPresent(), "revoke된 유예 토큰은 재회전 불가");
		assertFalse(s.validate(t).isPresent());
	}

	@Test
	void graceExpires() throws InterruptedException {
		RefreshTokenStore s = store(Duration.ofMillis(80));
		String t = s.issue(10L);
		s.rotate(t).orElseThrow();
		Thread.sleep(300);
		assertFalse(s.rotate(t).isPresent(), "유예창 만료 후 이전 토큰 무효");
		assertFalse(s.validate(t).isPresent());
	}

	@Test
	void graceReuseDoesNotExtendGraceTtl() throws InterruptedException {
		// grace=400ms. t=300ms 재사용(유효) 후 t=550ms 재확인:
		// 연장 없으면 400ms에 만료(→empty 기대), 연장 버그면 300+400=700ms까지 살아있어 실패.
		RefreshTokenStore s = store(Duration.ofMillis(400));
		String t = s.issue(11L);
		s.rotate(t).orElseThrow();
		Thread.sleep(300);
		assertTrue(s.rotate(t).isPresent(), "만료 전 재사용 가능");
		Thread.sleep(250);
		assertFalse(s.rotate(t).isPresent(), "유예 재사용이 TTL을 연장하면 안 됨");
	}
}
