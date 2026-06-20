package ai.devpath.platform.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.devpath.platform.config.AuthProperties;
import ai.devpath.platform.config.SecurityConfig;
import java.time.Instant;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

class JwtServiceTest {

	private JwtService newService() {
		AuthProperties props = new AuthProperties();
		props.setJwtSecret("test-secret-please-change-min-32-bytes-long-0123456789");
		SecurityConfig cfg = new SecurityConfig(props);
		SecretKey key = cfg.jwtSecretKey();
		JwtEncoder encoder = cfg.jwtEncoder(key);
		return new JwtService(encoder, props);
	}

	@Test
	void mintsDecodableAccessTokenWithSubjectAndRole() {
		JwtService svc = newService();
		String token = svc.mintAccessToken(42L, "LEARNER");

		AuthProperties props = new AuthProperties();
		props.setJwtSecret("test-secret-please-change-min-32-bytes-long-0123456789");
		SecurityConfig cfg = new SecurityConfig(props);
		JwtDecoder decoder = cfg.jwtDecoder(cfg.jwtSecretKey());
		Jwt jwt = decoder.decode(token);

		assertEquals("42", jwt.getSubject());
		assertEquals("LEARNER", jwt.getClaimAsString("role"));
		assertTrue(jwt.getExpiresAt().isAfter(Instant.now()));
	}
}
