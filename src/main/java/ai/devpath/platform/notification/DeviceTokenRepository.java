package ai.devpath.platform.notification;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {
	Optional<DeviceToken> findByToken(String token);
}
