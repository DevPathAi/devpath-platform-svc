package ai.devpath.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * 순수 단위 테스트 (Redis·Spring 컨텍스트 불요).
 * SecurityConfig.adminRoleConverter()의 role 클레임 → ROLE_ 권한 변환 로직을 검증한다.
 */
class AdminAuthzTest {

    private JwtAuthenticationConverter converterUnderTest() {
        SecurityConfig cfg = new SecurityConfig(authProps());
        return cfg.adminRoleConverter();
    }

    private AuthProperties authProps() {
        AuthProperties props = new AuthProperties();
        props.setJwtSecret("test-secret-please-change-min-32-bytes-long-0123456789");
        return props;
    }

    private Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    @Test
    void roleAdminClaim_yieldsRoleAdminAuthority() {
        JwtAuthenticationConverter conv = converterUnderTest();
        Jwt token = jwt(Map.of("sub", "1", "role", "ADMIN"));

        Collection<GrantedAuthority> authorities =
                (Collection<GrantedAuthority>) conv.convert(token).getAuthorities();

        // Spring Security 7 JwtAuthenticationConverter always appends FACTOR_BEARER;
        // we assert ROLE_ADMIN is present (the claim that guards /admin/**).
        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_ADMIN")
                .doesNotContain("ROLE_LEARNER");
    }

    @Test
    void roleLearnerClaim_yieldsRoleLearnerAuthority() {
        JwtAuthenticationConverter conv = converterUnderTest();
        Jwt token = jwt(Map.of("sub", "2", "role", "LEARNER"));

        Collection<GrantedAuthority> authorities =
                (Collection<GrantedAuthority>) conv.convert(token).getAuthorities();

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_LEARNER")
                .doesNotContain("ROLE_ADMIN");
    }

    @Test
    void noRoleClaim_yieldsNoRoleAuthority() {
        JwtAuthenticationConverter conv = converterUnderTest();
        Jwt token = jwt(Map.of("sub", "3"));

        Collection<GrantedAuthority> authorities =
                (Collection<GrantedAuthority>) conv.convert(token).getAuthorities();

        // Spring Security 7 adds FACTOR_BEARER always, but no ROLE_* should be present.
        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .doesNotContain("ROLE_ADMIN", "ROLE_LEARNER")
                .noneMatch(a -> a.startsWith("ROLE_"));
    }
}
