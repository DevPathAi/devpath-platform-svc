package ai.devpath.platform.ads;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 슬롯별 적격 광고를 가중치 랜덤으로 1개 서빙한다. */
@Service
public class AdServeService {

  private final AdvertisementRepository ads;
  private final AdSettingsService settings;
  private final AdSlotConfigService slotConfigs;
  private final Random random;

  public AdServeService(AdvertisementRepository ads, AdSettingsService settings,
      AdSlotConfigService slotConfigs, Random random) {
    this.ads = ads;
    this.settings = settings;
    this.slotConfigs = slotConfigs;
    this.random = random;
  }

  @Transactional(readOnly = true)
  public Optional<AdSlotContent> serve(String slot, long userId) {
    if (!settings.isEnabled() || !userShouldSeeAds(userId)) {
      return Optional.empty();
    }
    AdSlotConfig config = slotConfigs.get(slot);
    return switch (config.getSource()) {
      case AdSlotSource.OFF -> Optional.empty();
      case AdSlotSource.ADSENSE -> adsense(config);
      default -> house(slot);
    };
  }

  /** 단위 ID가 없으면 접는다(미설정). */
  private Optional<AdSlotContent> adsense(AdSlotConfig config) {
    String unitId = config.getAdsenseSlotId();
    return unitId == null
        ? Optional.empty()
        : Optional.of(new AdSlotContent.Adsense(unitId));
  }

  private Optional<AdSlotContent> house(String slot) {
    List<Advertisement> eligible = ads.findEligible(AdSlot.parse(slot), Instant.now());
    if (eligible.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new AdSlotContent.House(AdView.of(pickWeighted(eligible))));
  }

  /**
   * 무료기간 게이팅 predicate. 지금은 전원 노출(전원 free). 후속 유료 티어 도입 시
   * user.tier == FREE 조건으로만 확장한다(결제 미의존).
   */
  private boolean userShouldSeeAds(long userId) {
    return true;
  }

  private Advertisement pickWeighted(List<Advertisement> eligible) {
    int total = eligible.stream().mapToInt(Advertisement::getWeight).sum();
    int r = random.nextInt(total);
    int cumulative = 0;
    for (Advertisement a : eligible) {
      cumulative += a.getWeight();
      if (r < cumulative) {
        return a;
      }
    }
    return eligible.get(eligible.size() - 1); // 방어(도달 불가)
  }
}
