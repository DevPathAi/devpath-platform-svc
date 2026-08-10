package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AdServeServiceTest {

  private final AdvertisementRepository repo = mock(AdvertisementRepository.class);
  private final AdSettingsService settings = mock(AdSettingsService.class);
  private final AdSlotConfigService slotConfigs = mock(AdSlotConfigService.class);

  private void slotSource(String source, String adsenseSlotId) {
    AdSlotConfig cfg = new AdSlotConfig();
    cfg.setSlot("DASHBOARD_TOP");
    cfg.setSource(source);
    cfg.setAdsenseSlotId(adsenseSlotId);
    when(slotConfigs.get(anyString())).thenReturn(cfg);
  }

  @Test
  void returnsEmptyWhenGloballyDisabled() {
    when(settings.isEnabled()).thenReturn(false);
    AdServeService svc = new AdServeService(repo, settings, slotConfigs, new Random(0));
    assertThat(svc.serve("DASHBOARD_TOP", 1L)).isEmpty();
  }

  @Test
  void returnsEmptyWhenSlotIsOff() {
    when(settings.isEnabled()).thenReturn(true);
    slotSource(AdSlotSource.OFF, null);
    AdServeService svc = new AdServeService(repo, settings, slotConfigs, new Random(0));
    assertThat(svc.serve("DASHBOARD_TOP", 1L)).isEmpty();
  }

  @Test
  void returnsEmptyWhenNoEligible() {
    when(settings.isEnabled()).thenReturn(true);
    slotSource(AdSlotSource.HOUSE, null);
    when(repo.findEligible(anyString(), any(Instant.class))).thenReturn(List.of());
    AdServeService svc = new AdServeService(repo, settings, slotConfigs, new Random(0));
    assertThat(svc.serve("DASHBOARD_TOP", 1L)).isEmpty();
  }

  @Test
  void weightedSelectionPicksByRandom() {
    when(settings.isEnabled()).thenReturn(true);
    slotSource(AdSlotSource.HOUSE, null);
    Advertisement a = ad(10L, "A", 1);
    Advertisement b = ad(20L, "B", 3); // 총 weight 4, [0,1)->A, [1,4)->B
    when(repo.findEligible(anyString(), any(Instant.class))).thenReturn(List.of(a, b));

    Random fixed = mock(Random.class);
    when(fixed.nextInt(4)).thenReturn(2); // 2 → 누적 A(1) 초과 → B
    AdServeService svc = new AdServeService(repo, settings, slotConfigs, fixed);

    Optional<AdSlotContent> content = svc.serve("DASHBOARD_TOP", 1L);
    assertThat(content).isPresent();
    assertThat(content.get()).isInstanceOf(AdSlotContent.House.class);
    assertThat(((AdSlotContent.House) content.get()).ad().id()).isEqualTo(20L);
  }

  @Test
  void returnsAdsenseWhenSlotIsAdsenseWithUnitId() {
    when(settings.isEnabled()).thenReturn(true);
    slotSource(AdSlotSource.ADSENSE, "1234567890");
    AdServeService svc = new AdServeService(repo, settings, slotConfigs, new Random(0));

    Optional<AdSlotContent> content = svc.serve("DASHBOARD_TOP", 1L);
    assertThat(content).isPresent();
    assertThat(content.get()).isInstanceOf(AdSlotContent.Adsense.class);
    assertThat(((AdSlotContent.Adsense) content.get()).adsenseSlotId()).isEqualTo("1234567890");
  }

  @Test
  void returnsEmptyWhenAdsenseHasNoUnitId() {
    when(settings.isEnabled()).thenReturn(true);
    slotSource(AdSlotSource.ADSENSE, null);
    AdServeService svc = new AdServeService(repo, settings, slotConfigs, new Random(0));
    assertThat(svc.serve("DASHBOARD_TOP", 1L)).isEmpty();
  }

  private Advertisement ad(long id, String title, int weight) {
    Advertisement a = new Advertisement();
    a.setTitle(title);
    a.setLinkUrl("https://e.com");
    a.setSlot("DASHBOARD_TOP");
    a.setWeight(weight);
    a.setStatus("ACTIVE");
    try {
      var f = Advertisement.class.getDeclaredField("id");
      f.setAccessible(true);
      f.set(a, id);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
    return a;
  }
}
