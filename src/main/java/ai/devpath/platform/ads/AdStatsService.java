package ai.devpath.platform.ads;

import ai.devpath.platform.ads.dto.AdStatsRow;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdStatsService {

  private final AdDailyStatsRepository stats;

  public AdStatsService(AdDailyStatsRepository stats) {
    this.stats = stats;
  }

  @Transactional(readOnly = true)
  public List<AdStatsRow> stats(long adId, LocalDate from, LocalDate to) {
    return stats.findByAdIdAndStatDateBetweenOrderByStatDate(adId, from, to).stream()
        .map(AdStatsRow::of)
        .toList();
  }
}
