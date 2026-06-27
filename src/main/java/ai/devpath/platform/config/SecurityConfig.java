package ai.devpath.platform.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
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

	/**
	 * 모바일 식별을 위해 기본 resolver를 래핑한다(authorize 요청의 client_type=mobile →
	 * state 마커). {@link ai.devpath.platform.auth.OAuth2LoginSuccessHandler}가 그 마커를 읽는다.
	 */
	@Bean
	public OAuth2AuthorizationRequestResolver authorizationRequestResolver(ClientRegistrationRepository repo) {
		return new ai.devpath.platform.auth.MobileAwareAuthorizationRequestResolver(
				new DefaultOAuth2AuthorizationRequestResolver(repo, "/oauth2/authorization"));
	}

	@Bean
	public SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			ai.devpath.platform.auth.OAuth2LoginSuccessHandler successHandler,
			OAuth2AuthorizationRequestResolver authorizationRequestResolver) throws Exception {
		http
			.csrf(csrf -> csrf.disable())
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/oauth2/**", "/login/**", "/auth/refresh", "/auth/logout", "/auth/oauth/token", "/actuator/health").permitAll()
				.anyRequest().authenticated())
			.oauth2Login(oauth -> oauth
				.authorizationEndpoint(a -> a.authorizationRequestResolver(authorizationRequestResolver))
				.successHandler(successHandler))
			.oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
		return http.build();
	}
}
