package ai.devpath.platform.release;

import java.util.LinkedHashMap;
import java.util.Map;

public record ReleaseAnalyticsEvent(String event, Map<String, Object> properties) {
	public ReleaseAnalyticsEvent {
		properties = Map.copyOf(new LinkedHashMap<>(properties));
	}
}
