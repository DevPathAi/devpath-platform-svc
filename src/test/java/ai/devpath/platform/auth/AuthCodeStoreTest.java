package ai.devpath.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Redis 통합테스트(CI). 일회용 인가 코드의 발급·소비·1회성. */
@SpringBootTest
@ActiveProfiles("test")
class AuthCodeStoreTest {

	@Autowired AuthCodeStore store;

	@Test
	void issueThenConsumeReturnsUserAndChallenge() {
		String code = store.issue(42L, "chal-xyz");
		Optional<AuthCodeStore.Consumed> c = store.consume(code);
		assertTrue(c.isPresent());
		assertEquals(42L, c.get().userId());
		assertEquals("chal-xyz", c.get().codeChallenge());
	}

	@Test
	void consumeIsOneTime() {
		String code = store.issue(1L, "c");
		assertTrue(store.consume(code).isPresent());
		assertTrue(store.consume(code).isEmpty(), "두 번째 소비는 불가(1회성)");
	}

	@Test
	void consumeUnknownIsEmpty() {
		assertTrue(store.consume("does-not-exist").isEmpty());
	}
}
