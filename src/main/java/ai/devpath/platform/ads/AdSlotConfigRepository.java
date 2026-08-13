package ai.devpath.platform.ads;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdSlotConfigRepository extends JpaRepository<AdSlotConfig, String> {
  List<AdSlotConfig> findAllByOrderBySlotAsc();
}
