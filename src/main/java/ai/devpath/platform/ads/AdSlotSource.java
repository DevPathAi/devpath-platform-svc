package ai.devpath.platform.ads;

import java.util.Set;

/** 유효 슬롯 소스 문자열 카탈로그. DB CHECK(chk_ad_slot_config_source)와 일치. */
public final class AdSlotSource {
  public static final String HOUSE = "HOUSE";
  public static final String ADSENSE = "ADSENSE";
  public static final String OFF = "OFF";

  private static final Set<String> ALL = Set.of(HOUSE, ADSENSE, OFF);

  private AdSlotSource() {}

  /** 유효 소스면 그대로 반환, 아니면 IllegalArgumentException(→400). */
  public static String parse(String source) {
    if (source == null || !ALL.contains(source)) {
      throw new IllegalArgumentException("알 수 없는 광고 소스: " + source);
    }
    return source;
  }
}
