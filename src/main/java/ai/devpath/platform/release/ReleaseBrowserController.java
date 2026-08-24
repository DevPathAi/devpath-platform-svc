package ai.devpath.platform.release;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/release/browser")
public class ReleaseBrowserController {
	public record CaptureRequest(String event, Map<String, Object> properties) {}

	private final ReleaseControlService control;

	public ReleaseBrowserController(ReleaseControlService control) {
		this.control = control;
	}

	@GetMapping("/analytics-permission")
	public Map<String, Object> permission(
			@RequestHeader(ReleaseHttp.CANDIDATE_HEADER) String candidate,
			@RequestHeader(ReleaseHttp.RUN_HEADER) String runKey) {
		var value = control.analyticsPermission(candidate, runKey);
		return ReleaseHttp.pinned(candidate,
			"granted", value.granted(),
			"analytics_origin", value.analyticsOrigin());
	}

	@PostMapping("/analytics-events")
	public Map<String, Object> capture(
			@RequestHeader(ReleaseHttp.CANDIDATE_HEADER) String candidate,
			@RequestHeader(ReleaseHttp.RUN_HEADER) String runKey,
			@RequestBody CaptureRequest request) {
		if (request == null || request.event() == null) {
			throw new ReleaseControlException("analytics request is invalid");
		}
		control.captureAnalytics(candidate, runKey, request.event(), request.properties());
		return ReleaseHttp.pinned(candidate, "accepted", true);
	}
}
