package ai.devpath.platform.release;

import ai.devpath.platform.user.UserRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ReleaseSandboxHooks implements ReleaseJourneyHooks {
	private static final Set<String> COMMANDS = Set.of(
		"next-run-immediate-disconnect",
		"next-run-midstream-disconnect",
		"next-run-timeout",
		"next-run-truncated",
		"seed-stale-allocating",
		"seed-stale-running");
	private static final Set<String> AI_COMMANDS = Set.of(
		"fail-next-review",
		"fail-next-mentor");
	private static final Set<String> LEARNING_COMMANDS = Set.of(
		"replay-claim",
		"replay-content-linked-completion",
		"replay-contentless-completion");
	private static final Set<String> CHECKPOINTS = Set.of(
		"session-id-within-one-second",
		"immediate-disconnect-timed-out",
		"owner-recovery-timed-out",
		"midstream-disconnect-completed",
		"owner-recovery-truncated",
		"stale-allocating-reconciled",
		"stale-running-reconciled");
	private static final Set<String> AI_CHECKPOINTS = Set.of(
		"partial-review-retains-run-and-review",
		"kafka-outbox-review-correlated",
		"private-mentor-prompt-committed",
		"mentor-partial-retained",
		"mentor-provider-payload-exact",
		"mentor-terminal-complete",
		"urls-logs-artifacts-clean",
		"sensitive-boundaries-clean");
	private static final Set<String> LCS_CHECKPOINTS = Set.of(
		"private-mentor-prompt-preview");
	private static final Set<String> LEARNING_CHECKPOINTS = Set.of(
		"guest-preview-owned-by-guest",
		"claim-replay-one-owned-result",
		"saved-preview-deep-equal",
		"authoritative-first-task",
		"authoritative-workspace-task",
		"workspace-context-parity",
		"content-linked-below-threshold",
		"content-linked-advanced-once",
		"contentless-advanced-once",
		"completion-replays-noop");

	private final ReleaseControlProperties properties;
	private final UserRepository users;
	private final RestClient rest;

	public ReleaseSandboxHooks(
			ReleaseControlProperties properties,
			UserRepository users,
			RestClient.Builder builder) {
		this(properties, users, builder.build());
	}

	ReleaseSandboxHooks(
			ReleaseControlProperties properties,
			UserRepository users,
			RestClient rest) {
		this.properties = properties;
		this.users = users;
		this.rest = rest;
	}

	@Override
	public void command(ReleaseRunState run, String command) {
		if (COMMANDS.contains(command)) {
			Map<String, Object> request = command.startsWith("seed-stale-")
				? Map.of("user_id", fixtureUserId(run))
				: Map.of();
			post(run, properties.getSandboxOrigin(), "sandbox", command, request);
			return;
		}
		if (AI_COMMANDS.contains(command)) {
			post(run, properties.getAiOrigin(), "ai", command,
				Map.of("user_id", fixtureUserId(run)));
			return;
		}
		if (LEARNING_COMMANDS.contains(command)) {
			post(run, properties.getLearningOrigin(), "learning", command,
				Map.of("user_id", fixtureUserId(run)));
			return;
		}
		if ("clear-faults".equals(command)) {
			post(run, properties.getSandboxOrigin(), "sandbox", command, Map.of());
			post(run, properties.getAiOrigin(), "ai", command,
				Map.of("user_id", fixtureUserId(run)));
		}
	}

	private void post(
			ReleaseRunState run,
			String origin,
			String service,
			String command,
			Map<String, Object> request) {
		Map<?, ?> response = rest.post()
			.uri(endpoint(run, origin, service, "/commands/" + command))
			.header("X-DevPath-Internal-Token", internalToken())
			.body(request)
			.retrieve()
			.body(Map.class);
		if (response == null || !Boolean.TRUE.equals(response.get("accepted"))) {
			throw new ReleaseControlException(service + " release command was rejected");
		}
	}

	@Override
	public boolean checkpoint(ReleaseRunState run, String checkpoint) {
		String origin;
		String service;
		if (CHECKPOINTS.contains(checkpoint)) {
			origin = properties.getSandboxOrigin();
			service = "sandbox";
		} else if (AI_CHECKPOINTS.contains(checkpoint)) {
			origin = properties.getAiOrigin();
			service = "ai";
		} else if (LCS_CHECKPOINTS.contains(checkpoint)) {
			origin = properties.getLcsOrigin();
			service = "lcs";
		} else if (LEARNING_CHECKPOINTS.contains(checkpoint)) {
			origin = properties.getLearningOrigin();
			service = "learning";
		} else {
			return false;
		}
		String suffix = "/checkpoints/" + checkpoint;
		if ("learning".equals(service)
				&& !"guest-preview-owned-by-guest".equals(checkpoint)) {
			suffix += "?user_id=" + fixtureUserId(run);
		}
		Map<?, ?> response = rest.get()
			.uri(endpoint(run, origin, service, suffix))
			.header("X-DevPath-Internal-Token", internalToken())
			.retrieve()
			.body(Map.class);
		return response != null && Boolean.TRUE.equals(response.get("passed"));
	}

	private long fixtureUserId(ReleaseRunState run) {
		return users.findByEmail(run.fixtureEmail())
			.map(ai.devpath.platform.user.User::getId)
			.orElseThrow(() -> new ReleaseControlException(
				"release fixture user is unavailable"));
	}

	private URI endpoint(
			ReleaseRunState run, String origin, String service, String suffix) {
		try {
			URI parsed = URI.create(origin);
			if (!("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme()))
					|| parsed.getHost() == null
					|| parsed.getUserInfo() != null
					|| parsed.getQuery() != null
					|| parsed.getFragment() != null
					|| !(parsed.getPath().isEmpty() || "/".equals(parsed.getPath()))) {
				throw new IllegalArgumentException();
			}
			return URI.create(origin.replaceAll("/$", "")
				+ "/internal/release/" + service + "/"
				+ run.candidateSpecSha256() + "/" + run.runKey() + suffix);
		} catch (RuntimeException exception) {
			throw new ReleaseControlException(service + " release origin is invalid");
		}
	}

	private String internalToken() {
		String value = properties.getInternalToken();
		if (value == null || value.getBytes(StandardCharsets.UTF_8).length < 16) {
			throw new ReleaseControlException("release internal credential is unavailable");
		}
		return value;
	}
}
