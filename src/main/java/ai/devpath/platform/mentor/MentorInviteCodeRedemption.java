package ai.devpath.platform.mentor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "mentor_invite_code_redemptions")
public class MentorInviteCodeRedemption {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "invite_code_id", nullable = false)
  private Long inviteCodeId;

  @Column(name = "user_id", nullable = false, unique = true)
  private Long userId;

  @Column(name = "redeemed_at", nullable = false)
  private Instant redeemedAt;

  public static MentorInviteCodeRedemption of(long inviteCodeId, long userId, Instant at) {
    MentorInviteCodeRedemption row = new MentorInviteCodeRedemption();
    row.inviteCodeId = inviteCodeId;
    row.userId = userId;
    row.redeemedAt = at;
    return row;
  }

  public Long getId() { return id; }
  public Long getInviteCodeId() { return inviteCodeId; }
  public Long getUserId() { return userId; }
  public Instant getRedeemedAt() { return redeemedAt; }
}
