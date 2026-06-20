package ai.devpath.platform.auth.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

	private RefreshTokenStore store() {
		AuthProperties props = new AuthProperties();
		props.setRefreshTtl(Duration.ofDays(14));
		return new RefreshTokenStore(redis, props);
	}

	@Test
	void issueValidateRotateRevoke() {
		RefreshTokenStore s = store();
		String t = s.issue(42L);
		assertEquals(42L, s.validate(t).orElseThrow());

		var rotated = s.rotate(t).orElseThrow();
		assertEquals(42L, rotated.userId());
		assertFalse(s.validate(t).isPresent(), "회전 후 이전 토큰 무효");
		assertEquals(42L, s.validate(rotated.newToken()).orElseThrow());

		s.revoke(rotated.newToken());
		assertFalse(s.validate(rotated.newToken()).isPresent(), "폐기 후 무효");
	}
}
