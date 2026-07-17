package ai.devpath.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

class MobileAwareAuthorizationRequestResolverTest {

    private OAuth2AuthorizationRequest baseReq() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://p/authorize")
                .clientId("c").redirectUri("https://cb").state("csrf123").build();
    }

    private MobileAwareAuthorizationRequestResolver resolver(OAuth2AuthorizationRequest base) {
        OAuth2AuthorizationRequestResolver delegate = mock(OAuth2AuthorizationRequestResolver.class);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(delegate.resolve(req)).thenReturn(base);
        // client_type/code_challenge stubbing done per-test via req
        this.req = req;
        return new MobileAwareAuthorizationRequestResolver(delegate);
    }

    private HttpServletRequest req;

    @Test
    void adminClientType_appendsAdminMarker() {
        var base = baseReq();
        var resolver = resolver(base);
        when(req.getParameter("client_type")).thenReturn("admin");
        when(req.getParameter("code_challenge")).thenReturn(null);

        var out = resolver.resolve(req);

        assertThat(out.getState()).isEqualTo("csrf123.admin.");
    }

    @Test
    void noClientType_leavesStateUnchanged() {
        var base = baseReq();
        var resolver = resolver(base);
        when(req.getParameter("client_type")).thenReturn(null);
        when(req.getParameter("code_challenge")).thenReturn(null);

        var out = resolver.resolve(req);

        assertThat(out.getState()).isEqualTo("csrf123");
    }
}
