package ai.devpath.platform.release;

import java.security.SecureRandom;
import java.util.Base64;

final class ReleaseTokens {
	private static final SecureRandom RANDOM = new SecureRandom();

	private ReleaseTokens() {}

	static String random() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
