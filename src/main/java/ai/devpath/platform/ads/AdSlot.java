package ai.devpath.platform.ads;

import java.util.Set;

/** 유효 슬롯 문자열 카탈로그. DB CHECK(chk_ad_slot)과 일치. */
public final class AdSlot {
  public static final String DASHBOARD_TOP = "DASHBOARD_TOP";
  public static final String COMMUNITY_FEED = "COMMUNITY_FEED";
  public static final String CONTENT_PAGE = "CONTENT_PAGE";

  private static final Set<String> ALL = Set.of(DASHBOARD_TOP, COMMUNITY_FEED, CONTENT_PAGE);

  private AdSlot() {}

  /** 유효 슬롯이면 그대로 반환, 아니면 IllegalArgumentException(→400). */
  public static String parse(String slot) {
    if (slot == null || !ALL.contains(slot)) {
      throw new IllegalArgumentException("유효하지 않은 슬롯: " + slot);
    }
    return slot;
  }
}
