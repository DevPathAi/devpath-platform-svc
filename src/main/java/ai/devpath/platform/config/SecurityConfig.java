package ai.devpath.platform.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private final AuthProperties props;

	public SecurityConfig(AuthProperties props) { this.props = props; }

	/**
	 * JWT {@code role} 클레임(예: {@code "ADMIN"}, {@code "LEARNER"})을
	 * 단일 {@code ROLE_<role>} authority로 변환한다.
	 * {@code role} 클레임이 없으면 빈 authority 목록을 반환한다.
	 */
	JwtAuthenticationConverter adminRoleConverter() {
		JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
		conv.setJwtGrantedAuthoritiesConverter(jwt -> {
			String role = jwt.getClaimAsString("role");
			return role == null ? java.util.List.of()
					: java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role));
		});
		return conv;
	}

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
	@Order(1)
	public SecurityFilterChain releaseSecurityFilterChain(HttpSecurity http) throws Exception {
		http
			.securityMatcher("/v1/release/**")
			.csrf(csrf -> csrf.disable())
			.cors(cors -> {})
			.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
		return http.build();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration release = new CorsConfiguration();
		release.setAllowedOrigins(java.util.List.of(
			"https://leva.ai.kr",
			"https://app.leva.ai.kr"));
		release.setAllowedMethods(java.util.List.of(
			HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.OPTIONS.name()));
		release.setAllowedHeaders(java.util.List.of(
			HttpHeaders.ACCEPT,
			HttpHeaders.CONTENT_TYPE,
			"X-Candidate-Spec-Sha256",
			"X-Release-Run-Key"));
		release.setAllowCredentials(false);
		release.setMaxAge(300L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/v1/release/browser/**", release);

		CorsConfiguration publicSupport = new CorsConfiguration();
		publicSupport.setAllowedOrigins(java.util.List.of(
			"https://leva.ai.kr",
			"http://localhost:4321",
			"http://127.0.0.1:4321"));
		publicSupport.setAllowedMethods(java.util.List.of(
			HttpMethod.POST.name(), HttpMethod.OPTIONS.name()));
		publicSupport.setAllowedHeaders(java.util.List.of(
			HttpHeaders.ACCEPT,
			HttpHeaders.CONTENT_TYPE));
		publicSupport.setAllowCredentials(false);
		publicSupport.setMaxAge(300L);
		source.registerCorsConfiguration("/support/public-requests", publicSupport);
		return source;
	}

	@Bean
	@Order(2)
	public SecurityFilterChain applicationSecurityFilterChain(
			HttpSecurity http,
			ai.devpath.platform.auth.OAuth2LoginSuccessHandler successHandler,
			ai.devpath.platform.auth.GithubEmailOAuth2UserService githubEmailService,
			OAuth2AuthorizationRequestResolver authorizationRequestResolver) throws Exception {
		http
			.csrf(csrf -> csrf.disable())
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/oauth2/**", "/login/**", "/auth/refresh", "/auth/logout", "/auth/oauth/token", "/actuator/health", "/actuator/health/**", "/beta/status").permitAll()
				.requestMatchers(HttpMethod.POST, "/support/public-requests").permitAll()
				.requestMatchers("/admin/**").hasRole("ADMIN")
				.anyRequest().authenticated())
			.oauth2Login(oauth -> oauth
				.authorizationEndpoint(a -> a.authorizationRequestResolver(authorizationRequestResolver))
				.userInfoEndpoint(u -> u.userService(githubEmailService))
				.successHandler(successHandler))
			.oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(adminRoleConverter())));
		return http.build();
	}
}
