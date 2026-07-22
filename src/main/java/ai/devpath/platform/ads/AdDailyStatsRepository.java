package ai.devpath.platform.ads;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdDailyStatsRepository extends JpaRepository<AdDailyStats, AdDailyStats.Key> {

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(value = "INSERT INTO ad_daily_stats (ad_id, stat_date, impressions, clicks) "
      + "VALUES (:adId, :date, 1, 0) "
      + "ON CONFLICT (ad_id, stat_date) DO UPDATE SET impressions = ad_daily_stats.impressions + 1",
      nativeQuery = true)
  void upsertImpression(@Param("adId") long adId, @Param("date") LocalDate date);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(value = "INSERT INTO ad_daily_stats (ad_id, stat_date, impressions, clicks) "
      + "VALUES (:adId, :date, 0, 1) "
      + "ON CONFLICT (ad_id, stat_date) DO UPDATE SET clicks = ad_daily_stats.clicks + 1",
      nativeQuery = true)
  void upsertClick(@Param("adId") long adId, @Param("date") LocalDate date);
}
