package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.platform.ads.dto.AdStatsRow;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdStatsServiceTest {

  @Autowired AdStatsService service;
  @Autowired AdEventService events;
  @Autowired AdvertisementRepository ads;

  @Test
  void statsReturnsDailyCounts() {
    Advertisement a = new Advertisement();
    a.setTitle("t"); a.setLinkUrl("https://e.com"); a.setSlot("DASHBOARD_TOP"); a.setStatus("ACTIVE"); a.setWeight(1);
    a = ads.save(a);
    events.record(a.getId(), "IMPRESSION");
    events.record(a.getId(), "CLICK");

    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    List<AdStatsRow> rows = service.stats(a.getId(), today.minusDays(1), today.plusDays(1));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).impressions()).isEqualTo(1L);
    assertThat(rows.get(0).clicks()).isEqualTo(1L);
  }
}
