package ai.devpath.platform.consent.dto;

import ai.devpath.platform.consent.ConsentType;
import java.util.List;

public record ConsentSubmitRequest(List<Item> consents, Integer birthYear) {
	public record Item(ConsentType type, boolean agreed) {}
}
