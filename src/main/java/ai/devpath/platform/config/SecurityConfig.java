package ai.devpath.platform.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
public class SecurityConfig {

	private final AuthProperties props;

	public SecurityConfig(AuthProperties props) { this.props = props; }

	@Bean
	public SecretKey jwtSecretKey() {
		byte[] bytes = props.getJwtSecret().getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) { // P1-3: HS256은 최소 256비트. 짧은 시크릿 부팅 실패.
			throw new IllegalStateException("JWT_SECRET must be >= 32 bytes (HS256), got " + bytes.length);
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	public JwtEncoder jwtEncoder(SecretKey key) {
		return new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key));
	}

	@Bean
	public JwtDecoder jwtDecoder(SecretKey key) {
		return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
	}
}
