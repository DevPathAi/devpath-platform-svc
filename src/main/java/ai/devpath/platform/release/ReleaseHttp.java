package ai.devpath.platform.release;

import java.util.LinkedHashMap;
import java.util.Map;

final class ReleaseHttp {
	static final String SCHEMA = "mission-spine.staging-control.v1";
	static final String CANDIDATE_HEADER = "X-Candidate-Spec-Sha256";
	static final String RUN_HEADER = "X-Release-Run-Key";

	private ReleaseHttp() {}

	static String bearer(String authorization) {
		if (authorization == null || !authorization.startsWith("Bearer ")
				|| authorization.length() == "Bearer ".length()) {
			throw new ReleaseControlException(
				ReleaseControlException.Kind.UNAUTHORIZED, "bearer credential is required");
		}
		return authorization.substring("Bearer ".length());
	}

	static Map<String, Object> pinned(String candidate, Object... values) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("schema_version", SCHEMA);
		body.put("candidate_spec_sha256", candidate);
		for (int index = 0; index < values.length; index += 2) {
			body.put((String) values[index], values[index + 1]);
		}
		return body;
	}
}
