package ai.devpath.platform.ads.dto;

import ai.devpath.platform.ads.AdSlotContent;
import ai.devpath.platform.ads.AdView;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * GET /ads 응답 봉투. 도메인의 sealed AdSlotContent를 wire 표현으로 옮기는
 * 유일한 지점이며, 조립은 AdController에서만 한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdServeResponse(String type, AdView ad, String adsenseSlotId) {

  public static AdServeResponse from(AdSlotContent content) {
    return switch (content) {
      case AdSlotContent.House h -> new AdServeResponse("HOUSE", h.ad(), null);
      case AdSlotContent.Adsense a -> new AdServeResponse("ADSENSE", null, a.adsenseSlotId());
    };
  }
}
