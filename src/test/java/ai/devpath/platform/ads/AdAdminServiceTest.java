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

  // M4: update existing ad changes fields
  @Test
  void updateExistingAdChangesFields() {
    AdRow created = service.create(new AdRequest("원본", null, "https://orig.com", "DASHBOARD_TOP", 1, "ACTIVE", null, null));
    AdRow updated = service.update(created.id(), new AdRequest("수정됨", null, "https://new.com", "DASHBOARD_TOP", 3, "PAUSED", null, null));
    assertThat(updated.title()).isEqualTo("수정됨");
    assertThat(updated.linkUrl()).isEqualTo("https://new.com");
    assertThat(updated.weight()).isEqualTo(3);
    assertThat(updated.status()).isEqualTo("PAUSED");
  }

  // M4: update unknown id throws AdNotFoundException
  @Test
  void updateUnknownIdThrowsAdNotFoundException() {
    assertThatThrownBy(() -> service.update(999999L, new AdRequest("t", null, "https://e.com", "DASHBOARD_TOP", 1, "ACTIVE", null, null)))
        .isInstanceOf(AdNotFoundException.class);
  }

  // M4: delete existing then list is empty (for that slot)
  @Test
  void deleteExistingThenListIsEmpty() {
    AdRow created = service.create(new AdRequest("삭제대상", null, "https://del.com", "DASHBOARD_TOP", 1, "ACTIVE", null, null));
    service.delete(created.id());
    assertThat(service.list(null, null)).extracting(AdRow::id).doesNotContain(created.id());
  }

  // M4: delete unknown id throws AdNotFoundException
  @Test
  void deleteUnknownIdThrowsAdNotFoundException() {
    assertThatThrownBy(() -> service.delete(999999L))
        .isInstanceOf(AdNotFoundException.class);
  }

  // M4: blank linkUrl rejected
  @Test
  void createRejectsBlankLinkUrl() {
    assertThatThrownBy(() -> service.create(new AdRequest("t", null, "  ", "DASHBOARD_TOP", 1, "ACTIVE", null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
