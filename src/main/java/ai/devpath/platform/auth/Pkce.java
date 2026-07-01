package ai.devpath.platform.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * PKCE(RFC 7636) S256 도우미. {@code code_challenge = BASE64URL(SHA256(code_verifier))}.
 * verifier만 비밀이며 challenge는 공개값(인가 요청·state로 전달돼도 무방).
 */
public final class Pkce {

	private Pkce() {}

	/** verifier로부터 S256 challenge를 계산한다(ASCII, no-padding base64url). */
	public static String challengeS256(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** 제출된 verifier가 저장된 challenge와 S256으로 일치하는지. */
	public static boolean matches(String verifier, String challenge) {
		if (verifier == null || verifier.isBlank() || challenge == null || challenge.isBlank()) {
			return false;
		}
		return MessageDigest.isEqual(
				challenge.getBytes(StandardCharsets.US_ASCII),
				challengeS256(verifier).getBytes(StandardCharsets.US_ASCII));
	}
}
