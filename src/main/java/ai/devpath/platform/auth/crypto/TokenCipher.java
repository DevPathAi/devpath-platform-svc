package ai.devpath.platform.auth.crypto;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TokenCipher {

	private static final Logger log = LoggerFactory.getLogger(TokenCipher.class);
	private final Aead aead;

	public TokenCipher(@Value("${TOKEN_ENC_KEYSET:#{null}}") String keysetB64,
			org.springframework.core.env.Environment env) throws Exception {
		AeadConfig.register();
		KeysetHandle handle;
		if (keysetB64 == null || keysetB64.isBlank()) {
			// P1-2: 임시 인메모리 keyset은 local/test 프로파일에서만 허용. 그 외(prod 등)는 부팅 실패.
			var profiles = java.util.Arrays.asList(env.getActiveProfiles());
			boolean devOrTest = profiles.contains("local") || profiles.contains("test") || profiles.isEmpty();
			if (!devOrTest) {
				throw new IllegalStateException("TOKEN_ENC_KEYSET 미설정 — 운영 프로파일에서는 필수다(부팅 실패).");
			}
			log.warn("TOKEN_ENC_KEYSET 미설정 — 임시 인메모리 keyset 사용(local/test 전용, 재시작 시 기존 토큰 복호화 불가).");
			handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM);
		} else {
			String json = new String(Base64.getDecoder().decode(keysetB64), StandardCharsets.UTF_8);
			handle = TinkJsonProtoKeysetFormat.parseKeyset(json, InsecureSecretKeyAccess.get());
		}
		this.aead = handle.getPrimitive(Aead.class);
	}

	public String encrypt(String plaintext) {
		try {
			byte[] ct = aead.encrypt(plaintext.getBytes(StandardCharsets.UTF_8), new byte[0]);
			return Base64.getEncoder().encodeToString(ct);
		} catch (Exception e) {
			throw new IllegalStateException("토큰 암호화 실패", e);
		}
	}

	public String decrypt(String b64) {
		try {
			byte[] pt = aead.decrypt(Base64.getDecoder().decode(b64), new byte[0]);
			return new String(pt, StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new IllegalStateException("토큰 복호화 실패", e);
		}
	}
}
