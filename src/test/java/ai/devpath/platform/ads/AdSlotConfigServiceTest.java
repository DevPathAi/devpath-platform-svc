package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AdSlotConfigServiceTest {

  @Autowired AdSlotConfigService service;

  /**
   * 이 테스트 DB는 여러 테스트 클래스가 공유한다. 각 테스트가 슬롯 설정을 바꾸므로
   * 매번 시드 상태로 되돌려 놓아야 실행 순서에 상관없이 결정적으로 통과한다.
   */
  @AfterEach
  void restoreSeed() {
    for (String slot : new String[] {"DASHBOARD_TOP", "COMMUNITY_FEED", "CONTENT_PAGE"}) {
      service.update(slot, "HOUSE", null);
    }
  }

  @Test
  void listReturnsThreeSlotsInAscendingOrder() {
    var rows = service.list();
    assertThat(rows).hasSize(3);
    assertThat(rows.stream().map(AdSlotConfig::getSlot))
        .containsExactly("COMMUNITY_FEED", "CONTENT_PAGE", "DASHBOARD_TOP");
  }

  @Test
  void updateStoresSourceAndNormalizesBlankSlotIdToNull() {
    service.update("DASHBOARD_TOP", "ADSENSE", "  1234567890  ");
    assertThat(service.get("DASHBOARD_TOP").getSource()).isEqualTo("ADSENSE");
    assertThat(service.get("DASHBOARD_TOP").getAdsenseSlotId()).isEqualTo("1234567890");

    service.update("DASHBOARD_TOP", "ADSENSE", "   ");
    assertThat(service.get("DASHBOARD_TOP").getAdsenseSlotId()).isNull();
  }

  @Test
  void updateRejectsUnknownSource() {
    assertThatThrownBy(() -> service.update("DASHBOARD_TOP", "BANNERFLOW", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void updateRejectsUnknownSlot() {
    assertThatThrownBy(() -> service.update("SIDEBAR", "HOUSE", null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
