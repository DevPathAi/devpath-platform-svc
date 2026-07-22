package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.devpath.platform.ads.dto.AdRequest;
import ai.devpath.platform.ads.dto.AdRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdAdminServiceTest {

  @Autowired AdAdminService service;

  @Test
  void createThenListReturnsRow() {
    AdRow row = service.create(new AdRequest("배너", null, "https://e.com", "DASHBOARD_TOP", 2, "ACTIVE", null, null));
    assertThat(row.id()).isNotNull();
    assertThat(service.list(null, null)).extracting(AdRow::title).contains("배너");
  }

  @Test
  void createRejectsBlankTitle() {
    assertThatThrownBy(() -> service.create(new AdRequest(" ", null, "https://e.com", "DASHBOARD_TOP", 1, "ACTIVE", null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsInvalidSlot() {
    assertThatThrownBy(() -> service.create(new AdRequest("t", null, "https://e.com", "NOPE", 1, "ACTIVE", null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsWeightBelowOne() {
    assertThatThrownBy(() -> service.create(new AdRequest("t", null, "https://e.com", "DASHBOARD_TOP", 0, "ACTIVE", null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
