package ai.devpath.platform.release;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ReleaseControlService {
	public record Prerequisites(boolean ready, List<String> capabilities) {}
	public record Prepared(String runKey, String fixtureRevision) {}
	public record CommandResult(boolean accepted) {}
	public record Permission(boolean granted, String analyticsOrigin) {}
	public record Checkpoint(boolean passed) {}

	private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final Pattern RUN_KEY = Pattern.compile("^[A-Za-z0-9_-]{22,128}$");
	private static final Pattern REVISION = Pattern.compile("^[0-9a-f]{40}$");
	private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
	private static final Set<String> JOURNEYS = Set.of(
		"mission-spine-onboarding",
		"mission-spine-workspace");
	private static final Map<String, Set<String>> COMMANDS = Map.of(
		"mission-spine-onboarding", Set.of(
			"replay-oauth-callback",
			"replay-claim",
			"replay-content-linked-completion",
			"replay-contentless-completion",
			"grant-analytics-permission"),
		"mission-spine-workspace", Set.of(
			"next-run-immediate-disconnect",
			"next-run-midstream-disconnect",
			"next-run-timeout",
			"next-run-truncated",
			"seed-stale-allocating",
			"seed-stale-running",
			"fail-next-review",
			"fail-next-mentor",
			"clear-faults",
			"grant-analytics-permission"));
	private static final Set<String> ANALYTICS_EVENTS = Set.of(
		"landing_viewed",
		"landing_diagnostic_cta_clicked",
		"diagnostic_started",
		"diagnostic_completed",
		"result_claimed",
		"path_generated",
		"existing_path_continued",
		"path_first_viewed",
		"first_mission_started",
		"first_practice_succeeded",
		"contextual_review_viewed");
	private static final Set<String> BANNED_ANALYTICS_PROPERTIES = Set.of(
		"email", "name", "full_name", "display_name", "nickname", "github_handle",
		"provider_subject", "oauth_code", "oauth_state", "access_token", "refresh_token",
		"guest_token", "password", "code", "editor_code", "output", "stdout", "stderr",
		"error", "prompt", "answer", "answers", "context_snapshot", "lcs_snapshot", "token");

	private final ReleaseControlProperties properties;
	private final ReleaseStateStore state;
	private final ReleaseFixtureProvisioner fixtures;
	private final ReleaseJourneyHooks hooks;
	private final Supplier<String> runKeys;

	@Autowired
	public ReleaseControlService(
			ReleaseControlProperties properties,
			ReleaseStateStore state,
			ReleaseFixtureProvisioner fixtures,
			ReleaseJourneyHooks hooks) {
		this(properties, state, fixtures, hooks, ReleaseTokens::random);
	}

	ReleaseControlService(
			ReleaseControlProperties properties,
			ReleaseStateStore state,
			ReleaseFixtureProvisioner fixtures,
			Supplier<String> runKeys) {
		this(properties, state, fixtures, ReleaseJourneyHooks.NONE, runKeys);
	}

	ReleaseControlService(
			ReleaseControlProperties properties,
			ReleaseStateStore state,
			ReleaseFixtureProvisioner fixtures,
			ReleaseJourneyHooks hooks,
			Supplier<String> runKeys) {
		this.properties = properties;
		this.state = state;
		this.fixtures = fixtures;
		this.hooks = hooks;
		this.runKeys = runKeys;
	}

	public Prerequisites prerequisites(String credential, String candidateSpecSha256, String journey) {
		requireControlCredential(credential);
		requireCandidate(candidateSpecSha256);
		requireJourney(journey);
		boolean ready = REVISION.matcher(properties.getFixtureRevision()).matches()
			&& !properties.getCapabilities().isEmpty();
		return new Prerequisites(ready, List.copyOf(properties.getCapabilities()));
	}

	public Prepared prepare(String credential, String candidateSpecSha256, String journey) {
		requireControlCredential(credential);
		requireCandidate(candidateSpecSha256);
		requireJourney(journey);
		if (!REVISION.matcher(properties.getFixtureRevision()).matches()) {
			throw new ReleaseControlException("release fixture revision is unavailable");
		}
		String runKey = runKeys.get();
		requireRunKey(runKey);
		String email = fixtureEmail(candidateSpecSha256, runKey);
		fixtures.provision(candidateSpecSha256, runKey, journey, email);
		ReleaseRunState prepared = new ReleaseRunState(
			candidateSpecSha256,
			runKey,
			journey,
			properties.getFixtureRevision(),
			email,
			false,
			List.of(),
			Set.of(),
			false,
			false);
		hooks.prepare(prepared);
		state.saveRun(prepared, properties.getRunTtl());
		return new Prepared(runKey, properties.getFixtureRevision());
	}

	public CommandResult command(
			String credential,
			String candidateSpecSha256,
			String runKey,
			String journey,
			String command) {
		return command(
			credential, candidateSpecSha256, runKey, journey, command, Map.of());
	}

	public CommandResult command(
			String credential,
			String candidateSpecSha256,
			String runKey,
			String journey,
			String command,
			Map<String, Object> commandData) {
		requireControlCredential(credential);
		ReleaseRunState run = requireRun(candidateSpecSha256, runKey, journey);
		if (!COMMANDS.get(journey).contains(command)) {
			throw new ReleaseControlException("release command is not approved");
		}
		if ("replay-oauth-callback".equals(command) && !run.oauthExchanged()) {
			throw new ReleaseControlException("deterministic OAuth has not completed");
		}
		Map<String, Object> normalizedData = requireCommandData(command, commandData);
		hooks.command(run, command, normalizedData);
		ReleaseRunState updated = "grant-analytics-permission".equals(command)
			? run.withAnalyticsPermission().withCommand(command)
			: run.withCommand(command);
		state.saveRun(updated, properties.getRunTtl());
		return new CommandResult(true);
	}

	private static Map<String, Object> requireCommandData(
			String command, Map<String, Object> commandData) {
		Map<String, Object> data = commandData == null ? Map.of() : commandData;
		if (!"fail-next-review".equals(command)) {
			if (!data.isEmpty()) {
				throw new ReleaseControlException("release command payload is not allowed");
			}
			return Map.of();
		}
		if (data.size() != 1 || !data.containsKey("prior_sandbox_session_id")) {
			throw new ReleaseControlException("prior sandbox session id is required");
		}
		Object rawSessionId = data.get("prior_sandbox_session_id");
		try {
			if (!(rawSessionId instanceof Number number)) throw new ArithmeticException();
			long sessionId = new BigDecimal(number.toString()).longValueExact();
			if (sessionId <= 0 || sessionId > MAX_SAFE_INTEGER) throw new ArithmeticException();
			return Map.of("prior_sandbox_session_id", sessionId);
		} catch (ArithmeticException | NumberFormatException invalid) {
			throw new ReleaseControlException("prior sandbox session id is invalid");
		}
	}

	public Permission analyticsPermission(String candidateSpecSha256, String runKey) {
		ReleaseRunState run = requireRun(candidateSpecSha256, runKey, null);
		return new Permission(run.analyticsPermission(), properties.getAnalyticsOrigin());
	}

	public void captureAnalytics(
			String candidateSpecSha256,
			String runKey,
			String event,
			Map<String, Object> propertiesValue) {
		ReleaseRunState run = requireRun(candidateSpecSha256, runKey, null);
		if (!run.analyticsPermission()) {
			throw new ReleaseControlException("analytics permission is not granted");
		}
		if (!ANALYTICS_EVENTS.contains(event)) {
			throw new ReleaseControlException("analytics event is not allowlisted");
		}
		if (propertiesValue == null || propertiesValue.size() > 32) {
			throw new ReleaseControlException("analytics properties are invalid");
		}
		for (var entry : propertiesValue.entrySet()) {
			if (BANNED_ANALYTICS_PROPERTIES.contains(entry.getKey().toLowerCase(java.util.Locale.ROOT))) {
				throw new ReleaseControlException("analytics payload contains a banned property");
			}
			Object value = entry.getValue();
			if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) {
				throw new ReleaseControlException("analytics properties must be flat scalars");
			}
		}
		if (run.analyticsEvents().stream().anyMatch(existing -> existing.event().equals(event))) {
			throw new ReleaseControlException("analytics event is a duplicate");
		}
		state.saveRun(
			run.withAnalyticsEvent(new ReleaseAnalyticsEvent(event, propertiesValue)),
			properties.getRunTtl());
	}

	public List<ReleaseAnalyticsEvent> analytics(
			String credential,
			String candidateSpecSha256,
			String runKey,
			String journey) {
		requireControlCredential(credential);
		return requireRun(candidateSpecSha256, runKey, journey).analyticsEvents();
	}

	public void completeLogin(String candidateSpecSha256, String runKey,
			ai.devpath.platform.user.User user) {
		ReleaseRunState run = requireRun(candidateSpecSha256, runKey, null);
		if (!run.oauthExchanged()) {
			throw new ReleaseControlException("deterministic OAuth has not completed");
		}
		if (user == null || !Objects.equals(run.fixtureEmail(), user.getEmail())) {
			throw new ReleaseControlException("release fixture user does not match the run");
		}
		hooks.login(run, user);
	}

	public Checkpoint checkpoint(
			String credential,
			String candidateSpecSha256,
			String runKey,
			String journey,
			String checkpoint) {
		requireControlCredential(credential);
		ReleaseRunState run = requireRun(candidateSpecSha256, runKey, journey);
		boolean passed = switch (checkpoint) {
			case "analytics-prepermission-zero" ->
				!run.analyticsPermission() && run.analyticsEvents().isEmpty();
			case "landing-production-artifact", "web-production-artifact" ->
				properties.getCapabilities().contains("production-artifact-probe");
			case "journey-handoff-consumed" -> run.analyticsEvents().stream()
				.anyMatch(event -> "landing_diagnostic_cta_clicked".equals(event.event()));
			case "deterministic-oauth-complete" -> run.oauthExchanged();
			case "sensitive-boundaries-clean" -> sensitiveStateClean(run)
				&& ("mission-spine-onboarding".equals(run.journey())
					|| hooks.checkpoint(run, checkpoint));
			default -> hooks.checkpoint(run, checkpoint);
		};
		return new Checkpoint(passed);
	}

	private boolean sensitiveStateClean(ReleaseRunState run) {
		if (!run.oauthExchanged() || !run.analyticsPermission()
				|| run.analyticsEvents().isEmpty()) return false;
		for (ReleaseAnalyticsEvent event : run.analyticsEvents()) {
			if (!ANALYTICS_EVENTS.contains(event.event()) || event.properties() == null) return false;
			for (var entry : event.properties().entrySet()) {
				if (BANNED_ANALYTICS_PROPERTIES.contains(
						entry.getKey().toLowerCase(java.util.Locale.ROOT))) return false;
				Object value = entry.getValue();
				if (value != null
						&& !(value instanceof String || value instanceof Number || value instanceof Boolean)) {
					return false;
				}
			}
		}
		return true;
	}

	ReleaseRunState requireRun(String candidateSpecSha256, String runKey, String journey) {
		requireEnabled();
		requireCandidate(candidateSpecSha256);
		requireRunKey(runKey);
		ReleaseRunState run = state.findRun(candidateSpecSha256, runKey)
			.orElseThrow(() -> new ReleaseControlException(
				ReleaseControlException.Kind.NOT_FOUND, "release run is unavailable"));
		if (journey != null && !run.journey().equals(journey)) {
			throw new ReleaseControlException("release run journey mismatch");
		}
		return run;
	}

	void markOauthIssued(String candidateSpecSha256, String runKey) {
		state.saveRun(requireRun(candidateSpecSha256, runKey, null).withOauthIssued(), properties.getRunTtl());
	}

	void markOauthExchanged(String candidateSpecSha256, String runKey) {
		ReleaseRunState run = requireRun(candidateSpecSha256, runKey, null);
		if (!run.oauthIssued()) throw new ReleaseControlException("OAuth code was not issued");
		state.saveRun(run.withOauthExchanged(), properties.getRunTtl());
	}

	private void requireControlCredential(String credential) {
		requireEnabled();
		byte[] expected = properties.getControlToken().getBytes(StandardCharsets.UTF_8);
		byte[] actual = credential == null ? new byte[0] : credential.getBytes(StandardCharsets.UTF_8);
		if (expected.length < 16 || !MessageDigest.isEqual(expected, actual)) {
			throw new ReleaseControlException(
				ReleaseControlException.Kind.UNAUTHORIZED, "release control credential is invalid");
		}
	}

	private void requireEnabled() {
		if (!properties.isEnabled()) throw new ReleaseControlException(
			ReleaseControlException.Kind.NOT_FOUND, "release control is disabled");
	}

	private static void requireCandidate(String value) {
		if (value == null || !SHA256.matcher(value).matches()) {
			throw new ReleaseControlException("candidate-spec SHA256 is invalid");
		}
	}

	private static void requireRunKey(String value) {
		if (value == null || !RUN_KEY.matcher(value).matches()) {
			throw new ReleaseControlException("release run key is invalid");
		}
	}

	private static void requireJourney(String value) {
		if (!JOURNEYS.contains(value)) throw new ReleaseControlException("release journey is unknown");
	}

	private static String fixtureEmail(String candidate, String runKey) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest((candidate + ":" + runKey).getBytes(StandardCharsets.UTF_8));
			return "release+" + HexFormat.of().formatHex(digest, 0, 12) + "@staging.leva.invalid";
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}
}
