package ai.devpath.platform.ads.dto;

import ai.devpath.platform.ads.AdDailyStats;
import java.time.LocalDate;

public record AdStatsRow(LocalDate date, long impressions, long clicks) {
  public static AdStatsRow of(AdDailyStats s) {
    return new AdStatsRow(s.getStatDate(), s.getImpressions(), s.getClicks());
  }
}
