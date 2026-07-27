package ai.devpath.platform.consent.dto;

import ai.devpath.platform.consent.ConsentType;
import java.time.Instant;
import java.util.List;

public record ConsentStatusView(String consentStatus, List<Item> items, Integer birthYear) {
	public record Item(ConsentType type, boolean agreed, String version, Instant agreedAt) {}
}
