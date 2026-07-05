package ai.devpath.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import no.nav.security.mock.oauth2.MockOAuth2Server;
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 웹 OAuth 로그인 전 흐름 e2e(R4): mock provider로 authorize→callback→refresh 쿠키→
 * /auth/refresh→user를 실 Spring Security 필터체인으로 검증. 실 GitHub/Google은 런북 수동검증
 * (Google OIDC는 mock id_token/discovery 통합 이슈로 e2e 제외 — 핵심 로직은 UserRegistrationServiceTest·
 * OAuth2LoginSuccessHandlerTest로 검증. registration.google 설정은 실서버용으로 유지).
 *
 * <p>주의: TestRestTemplate은 302를 자동 추적하지 않도록 리다이렉트를 수동 처리하고
 * (Location + Set-Cookie를 단계별로 이어붙임), state 바인딩용 세션 쿠키를 보존한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class OAuthWebLoginE2ETest {

	static final MockOAuth2Server MOCK = new MockOAuth2Server();
	static final long UNIQUE_GH_ID = 990000001L; // DB 충돌 회피용 고유 id
	static final String WEB_URL = "http://web.test";

	@BeforeAll
	static void startMock() {
		MOCK.start();
		// github provider는 비-OIDC(userinfo 기반). userinfo/token claims를 GitHub 형태로 구성.
		MOCK.enqueueCallback(new DefaultOAuth2TokenCallback(
				"github", String.valueOf(UNIQUE_GH_ID), "JWT", List.of(),
				Map.of(
						"id", UNIQUE_GH_ID,
						"login", "smoke-tester",
						"name", "Smoke Tester",
						"email", "smoke@devpath.test"),
				3600));
	}

	@AfterAll
	static void stopMock() {
		MOCK.shutdown();
	}

	@DynamicPropertySource
	static void oauthProps(DynamicPropertyRegistry r) {
		String issuer = "github";
		r.add("spring.security.oauth2.client.provider.github.authorization-uri",
				() -> MOCK.authorizationEndpointUrl(issuer).toString());
		r.add("spring.security.oauth2.client.provider.github.token-uri",
				() -> MOCK.tokenEndpointUrl(issuer).toString());
		r.add("spring.security.oauth2.client.provider.github.user-info-uri",
				() -> MOCK.userInfoUrl(issuer).toString());
		r.add("spring.security.oauth2.client.provider.github.jwk-set-uri",
				() -> MOCK.jwksUrl(issuer).toString());
		r.add("spring.security.oauth2.client.provider.github.user-name-attribute", () -> "id");
		r.add("spring.security.oauth2.client.registration.github.client-id", () -> "test-client");
		r.add("spring.security.oauth2.client.registration.github.client-secret", () -> "test-secret");
		// mock-oauth2-server의 authorize 파서(nimbus oidc-sdk)는 scope에 openid가 없으면
		// "The scope must include an openid value" ParseException(400)을 던진다(라이브러리
		// 실동작 확인, 2.1.10). GitHub은 비-OIDC라 실서비스 scope엔 openid가 없지만, 이 mock
		// 대상 e2e에서만 scope에 openid를 추가해 mock 파서를 통과시킨다(운영 설정 미변경).
		r.add("spring.security.oauth2.client.registration.github.scope",
				() -> List.of("openid", "read:user", "user:email"));
		r.add("devpath.auth.web-url", () -> WEB_URL);
	}

	@LocalServerPort int port;
	@Autowired TestRestTemplate restTemplate;

	@Test
	void webLoginFlow_redirectsToCallback_setsRefreshCookie_andRefreshReturnsUser() {
		TestRestTemplate rest = noRedirectRestTemplate();
		var flow = OAuthFlowDriver.run(rest, port, "/oauth2/authorization/github", WEB_URL);

		assertThat(flow.callbackLocation()).isEqualTo(WEB_URL + "/auth/callback");
		assertThat(flow.refreshCookie()).isNotNull();
		assertThat(flow.refreshCookie().toLowerCase()).contains("httponly");

		// /auth/refresh 로 세션 부트스트랩
		HttpHeaders h = new HttpHeaders();
		h.add(HttpHeaders.COOKIE, flow.refreshCookiePair());
		h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
		ResponseEntity<Map> res = rest.exchange("http://localhost:" + port + "/auth/refresh",
				HttpMethod.POST, new HttpEntity<>(h), Map.class);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(res.getBody().get("access_token")).asString().isNotBlank();
		@SuppressWarnings("unchecked")
		Map<String, Object> user = (Map<String, Object>) res.getBody().get("user");
		assertThat(String.valueOf(user.get("email"))).isEqualTo("smoke@devpath.test");
		assertThat(String.valueOf(user.get("nickname"))).isEqualTo("Smoke Tester");
	}

	private TestRestTemplate noRedirectRestTemplate() {
		var factory = new SimpleClientHttpRequestFactory() {
			@Override
			protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod)
					throws java.io.IOException {
				super.prepareConnection(connection, httpMethod);
				connection.setInstanceFollowRedirects(false);
			}
		};
		TestRestTemplate base = restTemplate.withBasicAuth(null, null);
		base.getRestTemplate().setRequestFactory(factory);
		return base;
	}

	/** authorize→(mock 로그인)→콜백까지 단계별로 리다이렉트를 수동 추적하는 드라이버. */
	static final class OAuthFlowDriver {

		record FlowResult(String callbackLocation, String refreshCookie, String refreshCookiePair) {}

		static FlowResult run(TestRestTemplate rest, int port, String startPath, String webUrl) {
			String base = "http://localhost:" + port;
			MultiValueMap<String, String> cookieJar = new LinkedMultiValueMap<>();

			// 1) GET /oauth2/authorization/github → 302 mock authorize (+세션 쿠키)
			ResponseEntity<String> step1 = get(rest, base + startPath, cookieJar);
			assertThat(step1.getStatusCode().value()).isEqualTo(302);
			absorbCookies(step1, cookieJar);
			String authorizeUrl = location(step1);

			// 2) mock authorize 페이지 GET → 로그인 폼(HTML) 또는 즉시 302(비대화형)
			ResponseEntity<String> authorizePage = get(rest, authorizeUrl, cookieJar);
			absorbCookies(authorizePage, cookieJar);

			ResponseEntity<String> afterLogin;
			if (authorizePage.getStatusCode().value() == 302) {
				afterLogin = authorizePage;
			} else {
				// 대화형 로그인 폼: username 파라미터로 자동 완료 제출.
				MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
				form.add("username", "smoke-tester");
				afterLogin = post(rest, authorizeUrl, form, cookieJar);
				absorbCookies(afterLogin, cookieJar);
			}
			assertThat(afterLogin.getStatusCode().value()).isEqualTo(302);
			String callbackUrl = location(afterLogin);

			// 3) 콜백 따라가기 → 302 Location = {webUrl}/auth/callback + Set-Cookie refresh
			ResponseEntity<String> step3 = get(rest, callbackUrl, cookieJar);
			assertThat(step3.getStatusCode().value()).isEqualTo(302);
			String refreshCookieHeader = step3.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
					.filter(c -> c.startsWith(RefreshCookies.COOKIE_NAME + "="))
					.findFirst()
					.orElse(null);
			String pair = refreshCookieHeader == null ? null : refreshCookieHeader.split(";", 2)[0];

			return new FlowResult(location(step3), refreshCookieHeader, pair);
		}

		private static ResponseEntity<String> get(TestRestTemplate rest, String url,
				MultiValueMap<String, String> cookieJar) {
			HttpHeaders headers = new HttpHeaders();
			String cookieHeader = toCookieHeader(cookieJar);
			if (!cookieHeader.isBlank()) headers.add(HttpHeaders.COOKIE, cookieHeader);
			return rest.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
		}

		private static ResponseEntity<String> post(TestRestTemplate rest, String url,
				MultiValueMap<String, String> form, MultiValueMap<String, String> cookieJar) {
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
			String cookieHeader = toCookieHeader(cookieJar);
			if (!cookieHeader.isBlank()) headers.add(HttpHeaders.COOKIE, cookieHeader);
			return rest.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
		}

		private static void absorbCookies(ResponseEntity<?> response, MultiValueMap<String, String> cookieJar) {
			List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
			if (setCookies == null) return;
			for (String setCookie : setCookies) {
				String pair = setCookie.split(";", 2)[0];
				int eq = pair.indexOf('=');
				if (eq <= 0) continue;
				String name = pair.substring(0, eq);
				cookieJar.set(name, pair.substring(eq + 1));
			}
		}

		private static String toCookieHeader(MultiValueMap<String, String> cookieJar) {
			StringBuilder sb = new StringBuilder();
			for (var entry : cookieJar.toSingleValueMap().entrySet()) {
				if (sb.length() > 0) sb.append("; ");
				sb.append(entry.getKey()).append('=').append(entry.getValue());
			}
			return sb.toString();
		}

		private static String location(ResponseEntity<?> response) {
			return response.getHeaders().getLocation() != null
					? response.getHeaders().getLocation().toString()
					: response.getHeaders().getFirst(HttpHeaders.LOCATION);
		}
	}
}
