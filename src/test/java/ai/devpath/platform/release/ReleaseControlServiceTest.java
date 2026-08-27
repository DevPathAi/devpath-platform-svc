package ai.devpath.platform.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import ai.devpath.platform.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReleaseControlServiceTest {

	private static final String TOKEN = "release-control-secret";
	private static final String CANDIDATE = "a".repeat(64);
	private static final String RUN_KEY = "A".repeat(43);
	private static final String JOURNEY = "mission-spine-onboarding";

	private final MemoryStateStore state = new MemoryStateStore();
	private final RecordingFixtureProvisioner fixtures = new RecordingFixtureProvisioner();
	private ReleaseControlService control;
	private ReleaseOAuthProviderService oauth;

	@BeforeEach
	void setUp() {
		ReleaseControlProperties properties = new ReleaseControlProperties();
		properties.setEnabled(true);
		properties.setControlToken(TOKEN);
		properties.setFixtureRevision("f".repeat(40));
		properties.setOauthClientId("release-client");
		properties.setOauthClientSecret("release-client-secret");
		properties.setOauthRedirectUri("https://api.leva.ai.kr/login/oauth2/code/release");
		properties.setAnalyticsOrigin("https://analytics-spy.staging.leva.ai.kr");
		properties.setRunTtl(Duration.ofMinutes(45));
		properties.setOauthCodeTtl(Duration.ofMinutes(1));
		properties.setOauthAccessTtl(Duration.ofMinutes(5));
		properties.setCapabilities(java.util.List.of(
			"production-artifact-probe",
			"deterministic-oauth",
			"analytics-spy",
			"analytics-prepermission-zero",
			"analytics-permission-control"));
		control = new ReleaseControlService(properties, state, fixtures, () -> RUN_KEY);
		oauth = new ReleaseOAuthProviderService(
			properties,
			state,
			control,
			() -> "C".repeat(43),
			() -> "T".repeat(43));
	}

	@Test
	void prepareRequiresControlTokenAndCreatesCandidateBoundFixture() {
		assertThatThrownBy(() -> control.prepare("wrong", CANDIDATE, JOURNEY))
			.isInstanceOf(ReleaseControlException.class)
			.hasMessageContaining("credential");

		var prepared = control.prepare(TOKEN, CANDIDATE, JOURNEY);

		assertThat(prepared.runKey()).isEqualTo(RUN_KEY);
		assertThat(prepared.fixtureRevision()).isEqualTo("f".repeat(40));
		assertThat(fixtures.lastEmail).matches("release\\+[0-9a-f]{24}@staging\\.leva\\.invalid");
		assertThat(state.findRun(CANDIDATE, RUN_KEY)).isPresent();
		assertThat(state.findRun("b".repeat(64), RUN_KEY)).isEmpty();
	}

	@Test
	void analyticsIsZeroBeforePermissionAndRejectsSensitiveOrDuplicateEvents() {
		control.prepare(TOKEN, CANDIDATE, JOURNEY);
		assertThat(control.analyticsPermission(CANDIDATE, RUN_KEY).granted()).isFalse();
		assertThat(control.checkpoint(
			TOKEN, CANDIDATE, RUN_KEY, JOURNEY, "analytics-prepermission-zero").passed())
			.isTrue();
		assertThatThrownBy(() -> control.captureAnalytics(
			CANDIDATE,
			RUN_KEY,
			"landing_viewed",
			Map.of("page_view_id", "P".repeat(22))))
			.isInstanceOf(ReleaseControlException.class)
			.hasMessageContaining("permission");

		control.command(TOKEN, CANDIDATE, RUN_KEY, JOURNEY, "grant-analytics-permission");
		assertThat(control.analyticsPermission(CANDIDATE, RUN_KEY).granted()).isTrue();
		assertThat(control.checkpoint(
			TOKEN, CANDIDATE, RUN_KEY, JOURNEY, "analytics-prepermission-zero").passed())
			.isFalse();
		assertThatThrownBy(() -> control.captureAnalytics(
			CANDIDATE,
			RUN_KEY,
			"landing_viewed",
			Map.of("email", "release@example.test")))
			.hasMessageContaining("banned");

		Map<String, Object> properties = Map.of(
			"contract_version", "mission-spine.analytics.v1",
			"page_view_id", "P".repeat(22));
		control.captureAnalytics(CANDIDATE, RUN_KEY, "landing_viewed", properties);
		assertThatThrownBy(() -> control.captureAnalytics(
			CANDIDATE, RUN_KEY, "landing_viewed", properties))
			.hasMessageContaining("duplicate");
		assertThat(control.analytics(TOKEN, CANDIDATE, RUN_KEY, JOURNEY))
			.extracting(ReleaseAnalyticsEvent::event)
			.containsExactly("landing_viewed");
	}

	@Test
	void oauthAuthorizationIsRunBoundAndCodesAndTokensAreSingleUse() {
		control.prepare(TOKEN, CANDIDATE, JOURNEY);
		assertThatThrownBy(() -> oauth.authorize(
			"b".repeat(64), RUN_KEY, "release-client", "response-state"))
			.hasMessageContaining("run");

		URI redirect = oauth.authorize(
			CANDIDATE, RUN_KEY, "release-client", "response-state");
		assertThat(redirect.toString()).isEqualTo(
			"https://api.leva.ai.kr/login/oauth2/code/release"
				+ "?code=" + "C".repeat(43) + "&state=response-state");

		assertThatThrownBy(() -> oauth.exchange(
			"C".repeat(43), "release-client", "wrong", "https://api.leva.ai.kr/login/oauth2/code/release"))
			.hasMessageContaining("client");
		var token = oauth.exchange(
			"C".repeat(43),
			"release-client",
			"release-client-secret",
			"https://api.leva.ai.kr/login/oauth2/code/release");
		assertThat(token.accessToken()).isEqualTo("T".repeat(43));
		assertThat(control.checkpoint(
			TOKEN, CANDIDATE, RUN_KEY, JOURNEY, "deterministic-oauth-complete").passed())
			.isTrue();
		assertThatThrownBy(() -> oauth.exchange(
			"C".repeat(43),
			"release-client",
			"release-client-secret",
			"https://api.leva.ai.kr/login/oauth2/code/release"))
			.hasMessageContaining("code");

		var user = oauth.userInfo("T".repeat(43));
		assertThat(user.get("id")).isEqualTo(fixtures.lastEmail);
		assertThat(user.get("email")).isEqualTo(fixtures.lastEmail);
		assertThatThrownBy(() -> oauth.userInfo("T".repeat(43)))
			.hasMessageContaining("token");
	}

	@Test
	void oauthReplayCommandRequiresACompletedProviderExchange() {
		control.prepare(TOKEN, CANDIDATE, JOURNEY);
		assertThatThrownBy(() -> control.command(
			TOKEN, CANDIDATE, RUN_KEY, JOURNEY, "replay-oauth-callback"))
			.hasMessageContaining("completed");

		oauth.authorize(CANDIDATE, RUN_KEY, "release-client", "response-state");
		oauth.exchange(
			"C".repeat(43),
			"release-client",
			"release-client-secret",
			"https://api.leva.ai.kr/login/oauth2/code/release");
		assertThat(control.command(
			TOKEN, CANDIDATE, RUN_KEY, JOURNEY, "replay-oauth-callback").accepted())
			.isTrue();
	}

	@Test
	void onboardingSensitiveBoundaryUsesOnlyCompletedOauthAndSanitizedAnalyticsState() {
		control.prepare(TOKEN, CANDIDATE, JOURNEY);
		oauth.authorize(CANDIDATE, RUN_KEY, "release-client", "response-state");
		oauth.exchange(
			"C".repeat(43),
			"release-client",
			"release-client-secret",
			"https://api.leva.ai.kr/login/oauth2/code/release");
		control.command(TOKEN, CANDIDATE, RUN_KEY, JOURNEY, "grant-analytics-permission");
		control.captureAnalytics(CANDIDATE, RUN_KEY, "landing_viewed",
			Map.of("contract_version", "mission-spine.analytics.v1"));

		assertThat(control.checkpoint(
			TOKEN, CANDIDATE, RUN_KEY, JOURNEY, "sensitive-boundaries-clean").passed())
			.isTrue();
	}

	@Test
	void localArtifactAndLandingHandoffCheckpointsRequireTheirObservedEvidence() {
		control.prepare(TOKEN, CANDIDATE, JOURNEY);

		assertThat(control.checkpoint(
			TOKEN, CANDIDATE, RUN_KEY, JOURNEY, "landing-production-artifact").passed())
			.isTrue();
		assertThat(control.checkpoint(
			TOKEN, CANDIDATE, RUN_KEY, JOURNEY, "journey-handoff-consumed").passed())
			.isFalse();

		control.command(TOKEN, CANDIDATE, RUN_KEY, JOURNEY, "grant-analytics-permission");
		control.captureAnalytics(CANDIDATE, RUN_KEY, "landing_diagnostic_cta_clicked",
			Map.of("contract_version", "mission-spine.analytics.v1"));

		assertThat(control.checkpoint(
			TOKEN, CANDIDATE, RUN_KEY, JOURNEY, "journey-handoff-consumed").passed())
			.isTrue();
	}

	@Test
	void downstreamHooksMustAcceptCommandsAndOwnNonlocalCheckpoints() {
		RecordingJourneyHooks hooks = new RecordingJourneyHooks();
		ReleaseControlProperties properties = properties();
		control = new ReleaseControlService(
			properties, state, fixtures, hooks, () -> RUN_KEY);
		control.prepare(TOKEN, CANDIDATE, "mission-spine-workspace");

		control.command(
			TOKEN,
			CANDIDATE,
			RUN_KEY,
			"mission-spine-workspace",
			"next-run-timeout");
		assertThat(hooks.lastCommand).isEqualTo("next-run-timeout");
		assertThat(control.checkpoint(
			TOKEN,
			CANDIDATE,
			RUN_KEY,
			"mission-spine-workspace",
			"owner-recovery-timed-out").passed()).isTrue();
		assertThat(hooks.lastCheckpoint).isEqualTo("owner-recovery-timed-out");
	}

	@Test
	void reviewFaultRequiresAndForwardsTheExactPriorSandboxSessionBinding() {
		RecordingJourneyHooks hooks = new RecordingJourneyHooks();
		control = new ReleaseControlService(
			properties(), state, fixtures, hooks, () -> RUN_KEY);
		control.prepare(TOKEN, CANDIDATE, "mission-spine-workspace");

		assertThatThrownBy(() -> control.command(
			TOKEN, CANDIDATE, RUN_KEY, "mission-spine-workspace", "fail-next-review"))
			.hasMessageContaining("prior sandbox session");
		assertThatThrownBy(() -> control.command(
			TOKEN, CANDIDATE, RUN_KEY, "mission-spine-workspace", "fail-next-review",
			Map.of("prior_sandbox_session_id", 0L)))
			.hasMessageContaining("prior sandbox session");
		assertThatThrownBy(() -> control.command(
			TOKEN, CANDIDATE, RUN_KEY, "mission-spine-workspace", "next-run-timeout",
			Map.of("prior_sandbox_session_id", 80L)))
			.hasMessageContaining("payload");

		control.command(
			TOKEN, CANDIDATE, RUN_KEY, "mission-spine-workspace", "fail-next-review",
			Map.of("prior_sandbox_session_id", 80L));

		assertThat(hooks.lastCommand).isEqualTo("fail-next-review");
		assertThat(hooks.lastCommandData)
			.isEqualTo(Map.of("prior_sandbox_session_id", 80L));
	}

	@Test
	void releaseLoginRequiresTheExactFixtureUserAndInvokesTheJourneyHook() {
		RecordingJourneyHooks hooks = new RecordingJourneyHooks();
		control = new ReleaseControlService(properties(), state, fixtures, hooks, () -> RUN_KEY);
		control.prepare(TOKEN, CANDIDATE, "mission-spine-workspace");
		User user = new User();
		user.setEmail(fixtures.lastEmail);
		assertThatThrownBy(() -> control.completeLogin(CANDIDATE, RUN_KEY, user))
			.hasMessageContaining("OAuth");
		state.saveRun(state.findRun(CANDIDATE, RUN_KEY).orElseThrow()
			.withOauthIssued().withOauthExchanged(), Duration.ofMinutes(45));

		control.completeLogin(CANDIDATE, RUN_KEY, user);
		assertThat(hooks.lastLogin).isSameAs(user);

		User wrong = new User();
		wrong.setEmail("different@staging.leva.invalid");
		assertThatThrownBy(() -> control.completeLogin(CANDIDATE, RUN_KEY, wrong))
			.hasMessageContaining("fixture user");
	}

	private static ReleaseControlProperties properties() {
		ReleaseControlProperties properties = new ReleaseControlProperties();
		properties.setEnabled(true);
		properties.setControlToken(TOKEN);
		properties.setFixtureRevision("f".repeat(40));
		return properties;
	}

	private static final class RecordingJourneyHooks implements ReleaseJourneyHooks {
		String lastCommand;
		Map<String, Object> lastCommandData;
		String lastCheckpoint;
		User lastLogin;

		@Override
		public void login(ReleaseRunState run, User user) {
			lastLogin = user;
		}

		@Override
		public void command(ReleaseRunState run, String command) {
			lastCommand = command;
		}

		@Override
		public void command(
				ReleaseRunState run, String command, Map<String, Object> commandData) {
			lastCommand = command;
			lastCommandData = commandData;
		}

		@Override
		public boolean checkpoint(ReleaseRunState run, String checkpoint) {
			lastCheckpoint = checkpoint;
			return true;
		}
	}

	private static final class RecordingFixtureProvisioner implements ReleaseFixtureProvisioner {
		String lastEmail;

		@Override
		public void provision(String candidateSpecSha256, String runKey, String journey, String email) {
			lastEmail = email;
		}
	}

	private static final class MemoryStateStore implements ReleaseStateStore {
		private final Map<String, ReleaseRunState> runs = new HashMap<>();
		private final Map<String, ReleaseOAuthIdentity> codes = new HashMap<>();
		private final Map<String, ReleaseOAuthIdentity> tokens = new HashMap<>();

		private static String key(String candidate, String run) {
			return candidate + ":" + run;
		}

		@Override
		public Optional<ReleaseRunState> findRun(String candidateSpecSha256, String runKey) {
			return Optional.ofNullable(runs.get(key(candidateSpecSha256, runKey)));
		}

		@Override
		public void saveRun(ReleaseRunState value, Duration ttl) {
			runs.put(key(value.candidateSpecSha256(), value.runKey()), value);
		}

		@Override
		public void saveAuthorizationCode(String code, ReleaseOAuthIdentity identity, Duration ttl) {
			codes.put(code, identity);
		}

		@Override
		public Optional<ReleaseOAuthIdentity> consumeAuthorizationCode(String code) {
			return Optional.ofNullable(codes.remove(code));
		}

		@Override
		public void saveAccessToken(String token, ReleaseOAuthIdentity identity, Duration ttl) {
			tokens.put(token, identity);
		}

		@Override
		public Optional<ReleaseOAuthIdentity> consumeAccessToken(String token) {
			return Optional.ofNullable(tokens.remove(token));
		}
	}
}
