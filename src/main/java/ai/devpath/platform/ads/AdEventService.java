package ai.devpath.platform.ads;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdEventService {

  private final AdvertisementRepository ads;
  private final AdDailyStatsRepository stats;

  public AdEventService(AdvertisementRepository ads, AdDailyStatsRepository stats) {
    this.ads = ads;
    this.stats = stats;
  }

  @Transactional
  public void record(long adId, String type) {
    if (!ads.existsById(adId)) {
      throw new AdNotFoundException(adId);
    }
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    switch (AdEventType.parse(type)) {
      case IMPRESSION -> stats.upsertImpression(adId, today);
      case CLICK -> stats.upsertClick(adId, today);
    }
  }
}
