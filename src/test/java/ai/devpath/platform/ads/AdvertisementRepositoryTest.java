package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdvertisementRepositoryTest {

  @Autowired AdvertisementRepository repo;

  @Test
  void findEligibleReturnsActiveWithinSchedule() {
    Instant now = Instant.now();
    repo.save(ad("active-open", "DASHBOARD_TOP", "ACTIVE", null, null));
    repo.save(ad("paused", "DASHBOARD_TOP", "PAUSED", null, null));
    repo.save(ad("future", "DASHBOARD_TOP", "ACTIVE", now.plus(1, ChronoUnit.DAYS), null));
    repo.save(ad("ended", "DASHBOARD_TOP", "ACTIVE", null, now.minus(1, ChronoUnit.DAYS)));
    repo.save(ad("other-slot", "COMMUNITY_FEED", "ACTIVE", null, null));

    List<Advertisement> eligible = repo.findEligible("DASHBOARD_TOP", now);

    assertThat(eligible).extracting(Advertisement::getTitle).containsExactly("active-open");
  }

  private Advertisement ad(String title, String slot, String status, Instant starts, Instant ends) {
    Advertisement a = new Advertisement();
    a.setTitle(title);
    a.setLinkUrl("https://example.com");
    a.setSlot(slot);
    a.setStatus(status);
    a.setWeight(1);
    a.setStartsAt(starts);
    a.setEndsAt(ends);
    return a;
  }
}
