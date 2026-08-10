package ai.devpath.platform.ads.dto;

import ai.devpath.platform.ads.AdSlotConfig;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdSlotConfigView(String slot, String source, String adsenseSlotId) {
  public static AdSlotConfigView of(AdSlotConfig c) {
    return new AdSlotConfigView(c.getSlot(), c.getSource(), c.getAdsenseSlotId());
  }
}
