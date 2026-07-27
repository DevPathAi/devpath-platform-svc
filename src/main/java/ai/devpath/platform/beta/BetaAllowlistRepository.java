package ai.devpath.platform.beta;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BetaAllowlistRepository extends JpaRepository<BetaAllowlist, Long> {
    boolean existsByEmail(String email);
    Optional<BetaAllowlist> findByEmail(String email);
}
