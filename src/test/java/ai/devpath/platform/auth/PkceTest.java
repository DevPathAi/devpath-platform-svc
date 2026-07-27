package ai.devpath.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 순수 단위테스트. RFC 7636 Appendix B 테스트 벡터로 S256 계산을 검증. */
class PkceTest {

	static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
	static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

	@Test
	void s256MatchesRfcVector() {
		assertEquals(CHALLENGE, Pkce.challengeS256(VERIFIER));
	}

	@Test
	void matchesAcceptsCorrectVerifier() {
		assertTrue(Pkce.matches(VERIFIER, CHALLENGE));
	}

	@Test
	void matchesRejectsWrongVerifier() {
		assertFalse(Pkce.matches("wrong-verifier", CHALLENGE));
	}

	@Test
	void matchesRejectsNullOrBlank() {
		assertFalse(Pkce.matches(VERIFIER, null));
		assertFalse(Pkce.matches(null, CHALLENGE));
		assertFalse(Pkce.matches("", CHALLENGE));
	}
}
