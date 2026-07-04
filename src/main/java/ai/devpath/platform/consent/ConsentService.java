package ai.devpath.platform.consent;

import ai.devpath.platform.consent.dto.ConsentStatusView;
import ai.devpath.platform.consent.dto.ConsentSubmitRequest;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.time.Instant;
import java.time.Year;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsentService {

	private static final int MIN_AGE = 14;

	private final ConsentRepository consents;
	private final UserRepository users;

	public ConsentService(ConsentRepository consents, UserRepository users) {
		this.consents = consents;
		this.users = users;
	}

	@Transactional
	public void submit(Long userId, ConsentSubmitRequest request) {
		Integer birthYear = request.birthYear();
		if (birthYear != null && Year.now().getValue() - birthYear < MIN_AGE) {
			throw new IllegalArgumentException("만 14세 미만은 가입할 수 없습니다");
		}

		User user = users.findById(userId)
				.orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));

		Instant now = Instant.now();
		for (ConsentSubmitRequest.Item item : request.consents()) {
			Consent c = new Consent();
			c.setUserId(userId);
			c.setType(item.type());
			c.setVersion(item.type().version);
			c.setAgreed(item.agreed());
			c.setAgreedAt(now);
			consents.save(c);
		}

		if (birthYear != null) {
			user.setBirthYear(birthYear);
		}

		if (requiredConsentsAgreed(userId)) {
			user.setConsentStatus("DONE");
		}
		users.save(user);
	}

	@Transactional(readOnly = true)
	public ConsentStatusView statusOf(Long userId) {
		User user = users.findById(userId)
				.orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));

		List<ConsentStatusView.Item> items = latestPerType(userId).stream()
				.map(c -> new ConsentStatusView.Item(c.getType(), c.isAgreed(), c.getVersion(), c.getAgreedAt()))
				.toList();

		return new ConsentStatusView(user.getConsentStatus(), items, user.getBirthYear());
	}

	@Transactional
	public void revoke(Long userId, ConsentType type) {
		if (type.required) {
			throw new ConsentRevokeConflictException("필수 동의 항목은 철회할 수 없습니다: " + type);
		}

		Consent latest = consents.findFirstByUserIdAndTypeOrderByAgreedAtDesc(userId, type)
				.orElseThrow(() -> new IllegalArgumentException("동의 이력이 없습니다: " + type));
		latest.setAgreed(false);
		latest.setRevokedAt(Instant.now());
		consents.save(latest);
	}

	private boolean requiredConsentsAgreed(Long userId) {
		List<Consent> latest = latestPerType(userId);
		for (ConsentType type : ConsentType.values()) {
			if (!type.required) continue;
			boolean agreed = latest.stream().anyMatch(c -> c.getType() == type && c.isAgreed());
			if (!agreed) return false;
		}
		return true;
	}

	private List<Consent> latestPerType(Long userId) {
		return consents.findByUserIdOrderByAgreedAtDesc(userId).stream()
				.filter(distinctByType())
				.toList();
	}

	private static java.util.function.Predicate<Consent> distinctByType() {
		java.util.Set<ConsentType> seen = java.util.EnumSet.noneOf(ConsentType.class);
		return c -> seen.add(c.getType());
	}
}
