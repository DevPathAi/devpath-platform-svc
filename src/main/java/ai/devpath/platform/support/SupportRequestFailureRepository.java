package ai.devpath.platform.support;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportRequestFailureRepository
    extends JpaRepository<SupportRequestFailure, Long> {

  List<SupportRequestFailure> findByRequestIdOrderBySeqAsc(long requestId);

  long countByRequestId(long requestId);
}
