package ai.devpath.platform.ads;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "ad_daily_stats")
@IdClass(AdDailyStats.Key.class)
public class AdDailyStats {
  @Id
  @Column(name = "ad_id")
  private Long adId;

  @Id
  @Column(name = "stat_date")
  private LocalDate statDate;

  private long impressions;
  private long clicks;

  public Long getAdId() { return adId; }
  public LocalDate getStatDate() { return statDate; }
  public long getImpressions() { return impressions; }
  public long getClicks() { return clicks; }

  public static class Key implements Serializable {
    private Long adId;
    private LocalDate statDate;
    public Key() {}
    public Key(Long adId, LocalDate statDate) { this.adId = adId; this.statDate = statDate; }
    @Override public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Key k)) return false;
      return Objects.equals(adId, k.adId) && Objects.equals(statDate, k.statDate);
    }
    @Override public int hashCode() { return Objects.hash(adId, statDate); }
  }
}
