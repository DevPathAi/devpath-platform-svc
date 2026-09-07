package ai.devpath.platform.mentor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "mentor_invite_codes")
public class MentorInviteCode {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "code_hash", nullable = false, unique = true, columnDefinition = "char(64)")
  private String codeHash;

  @Column(nullable = false)
  private String label;

  @Column(nullable = false)
  private String audience;

  @Column(nullable = false)
  private String cohort;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "max_redemptions", nullable = false)
  private int maxRedemptions;

  @Column(name = "redemption_count", nullable = false)
  private int redemptionCount;

  @Column(nullable = false)
  private boolean enabled = true;

  @Column(name = "created_by", nullable = false)
  private Long createdBy;

  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "disabled_by")
  private Long disabledBy;

  @Column(name = "disabled_at")
  private Instant disabledAt;

  @Column(name = "disable_reason")
  private String disableReason;

  public static MentorInviteCode create(
      String codeHash,
      String label,
      String audience,
      String cohort,
      Instant expiresAt,
      int maxRedemptions,
      long createdBy) {
    MentorInviteCode code = new MentorInviteCode();
    code.codeHash = codeHash;
    code.label = label;
    code.audience = audience;
    code.cohort = cohort;
    code.expiresAt = expiresAt;
    code.maxRedemptions = maxRedemptions;
    code.createdBy = createdBy;
    return code;
  }

  public void redeem(Instant now) {
    if (!isUsableAt(now)) throw new IllegalStateException("invite code is not usable");
    redemptionCount += 1;
  }

  public boolean isExpiredAt(Instant now) { return !expiresAt.isAfter(now); }
  public boolean isExhausted() { return redemptionCount >= maxRedemptions; }
  public boolean isUsableAt(Instant now) { return enabled && !isExpiredAt(now) && !isExhausted(); }

  public void disable(long actorId, String reason, Instant now) {
    enabled = false;
    disabledBy = actorId;
    disabledAt = now;
    disableReason = reason;
  }

  public Long getId() { return id; }
  public String getCodeHash() { return codeHash; }
  public String getLabel() { return label; }
  public String getAudience() { return audience; }
  public String getCohort() { return cohort; }
  public Instant getExpiresAt() { return expiresAt; }
  public int getMaxRedemptions() { return maxRedemptions; }
  public int getRedemptionCount() { return redemptionCount; }
  public boolean isEnabled() { return enabled; }
  public Long getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
  public Long getDisabledBy() { return disabledBy; }
  public Instant getDisabledAt() { return disabledAt; }
  public String getDisableReason() { return disableReason; }
}
