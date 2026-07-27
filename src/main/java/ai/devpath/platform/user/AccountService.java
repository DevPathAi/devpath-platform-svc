package ai.devpath.platform.user;

import ai.devpath.platform.auth.refresh.RefreshTokenStore;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

	private final UserRepository users;
	private final RefreshTokenStore refreshStore;

	public AccountService(UserRepository users, RefreshTokenStore refreshStore) {
		this.users = users;
		this.refreshStore = refreshStore;
	}

	@Transactional
	public void softDelete(long userId) {
		User user = users.findById(userId)
				.orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));
		user.setDeletedAt(Instant.now());
		users.save(user);
		refreshStore.revokeAll(userId);
	}
}
