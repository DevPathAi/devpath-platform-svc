package ai.devpath.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.platform.config.AuthProperties;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 순수 단위테스트(Redis 불요) — {@link AuthCodeStore}의 방어 분기 고정.
 *
 * <p>Redis 연동 happy-path는 {@link AuthCodeStoreTest}(@SpringBootTest, CI)가 담당한다.
 * 여기서는 StringRedisTemplate을 모킹해 다음 보안/저장 속성을 회귀 고정한다.
 * <ul>
 *   <li>손상/외부주입 값(구분자 없음·비숫자 userId)은 500이 아니라 깨끗한 empty(→401)로 처리.</li>
 *   <li>빈 challenge는 ""로 보존돼 교환 단계에서 PKCE 불일치(다운그레이드 차단)로 이어진다.</li>
 *   <li>code는 평문이 아니라 해시 키로 저장된다(Redis 덤프 노출 방지).</li>
 *   <li>소비는 GETDEL(원자적 1회성)로 수행된다.</li>
 * </ul>
 *
 * <p>저장 값 형태는 {@code "<userId><NUL><challenge>"}(구분자 = U+0000). NUL은 userId(숫자)나
 * base64url challenge에 등장하지 않아 모호함이 없다. 테스트는 {@link AuthCodeStore#SEP}를
 * 직접 참조해 구분자 표현이 바뀌어도 깨지지 않게 한다.
 */
class AuthCodeStoreUnitTest {

	private static final String SEP = String.valueOf(AuthCodeStore.SEP);

	private StringRedisTemplate redis;
	private ValueOperations<String, String> ops;
	private AuthCodeStore store;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		redis = mock(StringRedisTemplate.class);
		ops = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(ops);
		store = new AuthCodeStore(redis, new AuthProperties());
	}

	@Test
	void consumeCorruptedValueWithoutSeparatorIsEmpty() {
		when(ops.getAndDelete(anyString())).thenReturn("no-separator-here");
		assertTrue(store.consume("any-code").isEmpty(), "구분자 없는 손상 값 → empty(500 방지)");
	}

	@Test
	void consumeNonNumericUserIdIsEmpty() {
		// 구분자는 존재하지만 앞부분이 숫자가 아님 → parseLong이 던지고 흡수돼 empty.
		when(ops.getAndDelete(anyString())).thenReturn("notanumber" + SEP + "chal");
		assertTrue(store.consume("any-code").isEmpty(), "비숫자 userId → empty(NumberFormatException 흡수)");
	}

	@Test
	void consumeEmptyChallengeRoundTripsAsBlank() {
		// issue(userId, null) 이 저장하는 형태: "<userId><SEP>" (challenge 없음).
		when(ops.getAndDelete(anyString())).thenReturn("42" + SEP);
		Optional<AuthCodeStore.Consumed> c = store.consume("any-code");
		assertTrue(c.isPresent());
		assertEquals(42L, c.get().userId());
		assertEquals("", c.get().codeChallenge(), "빈 challenge는 ''로 보존(교환 단계에서 PKCE 불일치로 차단)");
		// 다운그레이드 차단 성질: 빈 challenge는 어떤 verifier와도 매칭되지 않는다.
		assertFalse(Pkce.matches("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", c.get().codeChallenge()));
	}

	@Test
	void consumeNullOrBlankCodeShortCircuitsWithoutRedis() {
		assertTrue(store.consume(null).isEmpty());
		assertTrue(store.consume("  ").isEmpty());
		// 빈 code는 Redis를 건드리지 않는다(불필요한 GETDEL 방지).
		verify(ops, never()).getAndDelete(anyString());
	}

	@Test
	void consumeUsesGetAndDeleteForAtomicSingleUse() {
		when(ops.getAndDelete(anyString())).thenReturn("7" + SEP + "chal");
		assertTrue(store.consume("the-code").isPresent());
		// 원자적 1회성: GET 후 DEL이 아니라 단일 GETDEL 이어야 한다.
		verify(ops).getAndDelete(anyString());
	}

	@Test
	void issueStoresHashedKeyNotPlaintextCodeWithTtl() {
		String code = store.issue(7L, "chal-xyz");

		ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
		verify(ops).set(key.capture(), value.capture(), ttl.capture());

		assertTrue(key.getValue().startsWith("authcode:"), "키 접두사");
		assertFalse(key.getValue().contains(code), "code 평문이 키에 들어가면 안 됨(해시 저장)");
		assertEquals("7" + SEP + "chal-xyz", value.getValue(), "값 = '<userId><SEP><challenge>'");
		assertEquals(Duration.ofSeconds(60), ttl.getValue(), "기본 authCodeTtl 적용");
	}

	@Test
	void issueWithNullChallengeStoresEmptyChallengeSegment() {
		store.issue(7L, null);
		verify(ops).set(anyString(), eq("7" + SEP), any(Duration.class));
	}
}
