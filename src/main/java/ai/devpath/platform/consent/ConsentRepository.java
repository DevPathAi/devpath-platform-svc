package ai.devpath.platform.consent;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentRepository extends JpaRepository<Consent, Long> {
	List<Consent> findByUserIdOrderByAgreedAtDesc(Long userId);
	Optional<Consent> findFirstByUserIdAndTypeOrderByAgreedAtDesc(Long userId, ConsentType type);
}
