package ai.devpath.platform.release;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/release")
public class ReleaseControlController {
	private final ReleaseControlService control;

	public ReleaseControlController(ReleaseControlService control) {
		this.control = control;
	}

	@GetMapping("/prerequisites/{journey}")
	public Map<String, Object> prerequisites(
			@RequestHeader(name = "Authorization", required = false) String authorization,
			@RequestHeader(ReleaseHttp.CANDIDATE_HEADER) String candidate,
			@PathVariable String journey) {
		var value = control.prerequisites(ReleaseHttp.bearer(authorization), candidate, journey);
		return ReleaseHttp.pinned(candidate,
			"ready", value.ready(),
			"capabilities", value.capabilities());
	}

	@PostMapping("/journeys/{journey}/prepare")
	public Map<String, Object> prepare(
			@RequestHeader(name = "Authorization", required = false) String authorization,
			@RequestHeader(ReleaseHttp.CANDIDATE_HEADER) String candidate,
			@PathVariable String journey) {
		var value = control.prepare(ReleaseHttp.bearer(authorization), candidate, journey);
		return ReleaseHttp.pinned(candidate,
			"run_key", value.runKey(),
			"fixture_revision", value.fixtureRevision());
	}

	@PostMapping("/journeys/{journey}/commands/{command}")
	public Map<String, Object> command(
			@RequestHeader(name = "Authorization", required = false) String authorization,
			@RequestHeader(ReleaseHttp.CANDIDATE_HEADER) String candidate,
			@RequestHeader(ReleaseHttp.RUN_HEADER) String runKey,
			@PathVariable String journey,
			@PathVariable String command,
			@RequestBody(required = false) Map<String, Object> body) {
		var value = control.command(
			ReleaseHttp.bearer(authorization), candidate, runKey, journey, command,
			body == null ? Map.of() : body);
		return ReleaseHttp.pinned(candidate, "accepted", value.accepted());
	}

	@GetMapping("/journeys/{journey}/checkpoints/{checkpoint}")
	public Map<String, Object> checkpoint(
			@RequestHeader(name = "Authorization", required = false) String authorization,
			@RequestHeader(ReleaseHttp.CANDIDATE_HEADER) String candidate,
			@RequestHeader(ReleaseHttp.RUN_HEADER) String runKey,
			@PathVariable String journey,
			@PathVariable String checkpoint) {
		var value = control.checkpoint(
			ReleaseHttp.bearer(authorization), candidate, runKey, journey, checkpoint);
		return ReleaseHttp.pinned(candidate, "result", value.passed() ? "passed" : "failed");
	}

	@GetMapping("/journeys/{journey}/analytics")
	public Map<String, Object> analytics(
			@RequestHeader(name = "Authorization", required = false) String authorization,
			@RequestHeader(ReleaseHttp.CANDIDATE_HEADER) String candidate,
			@RequestHeader(ReleaseHttp.RUN_HEADER) String runKey,
			@PathVariable String journey) {
		var value = control.analytics(
			ReleaseHttp.bearer(authorization), candidate, runKey, journey);
		return ReleaseHttp.pinned(candidate, "events", value);
	}
}
