package ai.devpath.platform.release;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReleaseHttpContractTest {
	private static final String CANDIDATE = "a".repeat(64);
	private static final String RUN_KEY = "R".repeat(43);
	private static final String JOURNEY = "mission-spine-onboarding";

	private final ReleaseControlService control = mock(ReleaseControlService.class);
	private final ReleaseOAuthProviderService oauth = mock(ReleaseOAuthProviderService.class);
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		ReleaseControlProperties properties = new ReleaseControlProperties();
		properties.setOauthRedirectUri("https://api.leva.ai.kr/login/oauth2/code/release");
		mvc = MockMvcBuilders.standaloneSetup(
			new ReleaseControlController(control),
			new ReleaseBrowserController(control),
			new ReleaseOAuthProviderController(oauth, properties))
			.setControllerAdvice(new ReleaseControlAdvice())
			.build();
	}

	@Test
	void controlResponsesAreSchemaAndCandidatePinned() throws Exception {
		when(control.prerequisites("control-secret", CANDIDATE, JOURNEY))
			.thenReturn(new ReleaseControlService.Prerequisites(true, List.of("analytics-spy")));
		when(control.prepare("control-secret", CANDIDATE, JOURNEY))
			.thenReturn(new ReleaseControlService.Prepared(RUN_KEY, "f".repeat(40)));
		when(control.command("control-secret", CANDIDATE, RUN_KEY, JOURNEY,
			"grant-analytics-permission", Map.of()))
			.thenReturn(new ReleaseControlService.CommandResult(true));
		when(control.checkpoint("control-secret", CANDIDATE, RUN_KEY, JOURNEY,
			"analytics-prepermission-zero"))
			.thenReturn(new ReleaseControlService.Checkpoint(true));

		mvc.perform(get("/v1/release/prerequisites/{journey}", JOURNEY)
				.header("Authorization", "Bearer control-secret")
				.header(ReleaseHttp.CANDIDATE_HEADER, CANDIDATE))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schema_version").value(ReleaseHttp.SCHEMA))
			.andExpect(jsonPath("$.candidate_spec_sha256").value(CANDIDATE))
			.andExpect(jsonPath("$.ready").value(true));

		mvc.perform(post("/v1/release/journeys/{journey}/prepare", JOURNEY)
				.header("Authorization", "Bearer control-secret")
				.header(ReleaseHttp.CANDIDATE_HEADER, CANDIDATE))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.run_key").value(RUN_KEY))
			.andExpect(jsonPath("$.fixture_revision").value("f".repeat(40)));

		mvc.perform(post("/v1/release/journeys/{journey}/commands/{command}",
				JOURNEY, "grant-analytics-permission")
				.header("Authorization", "Bearer control-secret")
				.header(ReleaseHttp.CANDIDATE_HEADER, CANDIDATE)
				.header(ReleaseHttp.RUN_HEADER, RUN_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accepted").value(true));
		verify(control).command(
			"control-secret", CANDIDATE, RUN_KEY, JOURNEY,
			"grant-analytics-permission", Map.of());

		mvc.perform(get("/v1/release/journeys/{journey}/checkpoints/{checkpoint}",
				JOURNEY, "analytics-prepermission-zero")
				.header("Authorization", "Bearer control-secret")
				.header(ReleaseHttp.CANDIDATE_HEADER, CANDIDATE)
				.header(ReleaseHttp.RUN_HEADER, RUN_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.result").value("passed"));
	}

	@Test
	void reviewFaultCommandForwardsThePriorSandboxSessionBinding() throws Exception {
		when(control.command(
			"control-secret", CANDIDATE, RUN_KEY, "mission-spine-workspace",
			"fail-next-review", Map.of("prior_sandbox_session_id", 80)))
			.thenReturn(new ReleaseControlService.CommandResult(true));

		mvc.perform(post("/v1/release/journeys/{journey}/commands/{command}",
				"mission-spine-workspace", "fail-next-review")
				.header("Authorization", "Bearer control-secret")
				.header(ReleaseHttp.CANDIDATE_HEADER, CANDIDATE)
				.header(ReleaseHttp.RUN_HEADER, RUN_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"prior_sandbox_session_id\":80}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accepted").value(true));

		verify(control).command(
			"control-secret", CANDIDATE, RUN_KEY, "mission-spine-workspace",
			"fail-next-review", Map.of("prior_sandbox_session_id", 80));
	}

	@Test
	void browserAnalyticsUsesOnlyRunBoundHeaders() throws Exception {
		when(control.analyticsPermission(CANDIDATE, RUN_KEY))
			.thenReturn(new ReleaseControlService.Permission(
				true, "https://analytics-spy.staging.leva.ai.kr"));

		mvc.perform(get("/v1/release/browser/analytics-permission")
				.header(ReleaseHttp.CANDIDATE_HEADER, CANDIDATE)
				.header(ReleaseHttp.RUN_HEADER, RUN_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.granted").value(true))
			.andExpect(jsonPath("$.analytics_origin")
				.value("https://analytics-spy.staging.leva.ai.kr"));

		mvc.perform(post("/v1/release/browser/analytics-events")
				.header(ReleaseHttp.CANDIDATE_HEADER, CANDIDATE)
				.header(ReleaseHttp.RUN_HEADER, RUN_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"event\":\"landing_viewed\",\"properties\":{\"page_view_id\":\"P123\"}}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accepted").value(true));
		verify(control).captureAnalytics(
			CANDIDATE, RUN_KEY, "landing_viewed", Map.of("page_view_id", "P123"));
	}

	@Test
	void oauthProviderPreservesStateAndUsesStandardTokenFields() throws Exception {
		String redirect = "https://api.leva.ai.kr/login/oauth2/code/release";
		when(oauth.authorize(CANDIDATE, RUN_KEY, "release-client", "state-value"))
			.thenReturn(URI.create(redirect + "?code=one-time-code&state=state-value"));
		when(oauth.exchange("one-time-code", "release-client", "release-secret", redirect))
			.thenReturn(new ReleaseOAuthProviderService.Token("one-time-token", "Bearer", 300));
		when(oauth.userInfo("one-time-token")).thenReturn(Map.of(
			"id", "release@example.test", "email", "release@example.test"));

		mvc.perform(get("/v1/release/oauth/authorize")
				.header(ReleaseHttp.CANDIDATE_HEADER, CANDIDATE)
				.header(ReleaseHttp.RUN_HEADER, RUN_KEY)
				.param("response_type", "code")
				.param("client_id", "release-client")
				.param("redirect_uri", redirect)
				.param("state", "state-value"))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", redirect + "?code=one-time-code&state=state-value"));

		String basic = Base64.getEncoder().encodeToString(
			"release-client:release-secret".getBytes(StandardCharsets.UTF_8));
		mvc.perform(post("/v1/release/oauth/token")
				.header("Authorization", "Basic " + basic)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("grant_type", "authorization_code")
				.param("code", "one-time-code")
				.param("redirect_uri", redirect))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.access_token").value("one-time-token"))
			.andExpect(jsonPath("$.token_type").value("Bearer"))
			.andExpect(jsonPath("$.expires_in").value(300));

		mvc.perform(get("/v1/release/oauth/userinfo")
				.header("Authorization", "Bearer one-time-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value("release@example.test"));
	}

	@Test
	void invalidCredentialsReturnOnlySanitizedFailure() throws Exception {
		mvc.perform(get("/v1/release/prerequisites/{journey}", JOURNEY)
				.header(ReleaseHttp.CANDIDATE_HEADER, CANDIDATE))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("release_request_rejected"))
			.andExpect(jsonPath("$.candidate_spec_sha256").doesNotExist());

		when(control.analyticsPermission(anyString(), anyString()))
			.thenThrow(new ReleaseControlException(
				ReleaseControlException.Kind.NOT_FOUND, "contains internal state"));
		mvc.perform(get("/v1/release/browser/analytics-permission")
				.header(ReleaseHttp.CANDIDATE_HEADER, CANDIDATE)
				.header(ReleaseHttp.RUN_HEADER, RUN_KEY))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("release_request_rejected"))
			.andExpect(jsonPath("$.message").doesNotExist());
	}
}
