package ai.devpath.platform.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserOauthIdentityRepository extends JpaRepository<UserOauthIdentity, Long> {
	Optional<UserOauthIdentity> findByProviderAndProviderUserId(String provider, String providerUserId);

	List<UserOauthIdentity> findByUserIdOrderByLinkedAtAsc(Long userId);
}
