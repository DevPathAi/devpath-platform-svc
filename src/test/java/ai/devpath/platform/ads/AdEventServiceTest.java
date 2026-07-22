package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdEventServiceTest {

  @Autowired AdEventService service;
  @Autowired AdvertisementRepository ads;
  @Autowired AdDailyStatsRepository stats;

  @Test
  void impressionUpsertsIncrement() {
    Advertisement a = ads.save(newAd());
    service.record(a.getId(), "IMPRESSION");
    service.record(a.getId(), "IMPRESSION");
    var today = LocalDate.now(ZoneOffset.UTC);
    assertThat(stats.findById(new AdDailyStats.Key(a.getId(), today)))
        .get().extracting(AdDailyStats::getImpressions).isEqualTo(2L);
  }

  @Test
  void clickUpsertsIncrement() {
    Advertisement a = ads.save(newAd());
    service.record(a.getId(), "CLICK");
    var today = LocalDate.now(ZoneOffset.UTC);
    assertThat(stats.findById(new AdDailyStats.Key(a.getId(), today)))
        .get().extracting(AdDailyStats::getClicks).isEqualTo(1L);
  }

  @Test
  void unknownAdThrowsNotFound() {
    assertThatThrownBy(() -> service.record(999999L, "IMPRESSION"))
        .isInstanceOf(AdNotFoundException.class);
  }

  private Advertisement newAd() {
    Advertisement a = new Advertisement();
    a.setTitle("t");
    a.setLinkUrl("https://e.com");
    a.setSlot("DASHBOARD_TOP");
    a.setWeight(1);
    a.setStatus("ACTIVE");
    return a;
  }
}
