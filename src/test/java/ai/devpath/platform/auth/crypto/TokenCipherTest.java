package ai.devpath.platform.auth.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class TokenCipherTest {

	@Test
	void encryptThenDecryptRoundtrips() throws Exception {
		// 활성 프로파일 없음(empty) → 임시 keyset 허용(P1-2).
		var env = new org.springframework.mock.env.MockEnvironment();
		TokenCipher cipher = new TokenCipher(null, env);
		String plain = "gho_exampletoken123";
		String enc = cipher.encrypt(plain);
		assertNotEquals(plain, enc);
		assertEquals(plain, cipher.decrypt(enc));
	}
}
