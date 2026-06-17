package ai.devpath.platform.notification;
import org.springframework.data.jpa.repository.JpaRepository;
public interface NotificationRepository extends JpaRepository<Notification, Long> {
	boolean existsByUserIdAndType(Long userId, String type);
	long countByUserId(Long userId);
}
