package ai.devpath.platform.ads;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 슬롯별 광고 소스 설정 1행. PK가 슬롯 문자열이라 행은 정확히 3개다. */
@Entity
@Table(name = "ad_slot_config")
public class AdSlotConfig {
  @Id
  private String slot;

  private String source = AdSlotSource.HOUSE;

  @Column(name = "adsense_slot_id")
  private String adsenseSlotId;

  public String getSlot() {
    return slot;
  }

  public void setSlot(String v) {
    this.slot = v;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String v) {
    this.source = v;
  }

  public String getAdsenseSlotId() {
    return adsenseSlotId;
  }

  public void setAdsenseSlotId(String v) {
    this.adsenseSlotId = v;
  }
}
