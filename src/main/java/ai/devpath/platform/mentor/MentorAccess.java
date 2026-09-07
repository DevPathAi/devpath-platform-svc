package ai.devpath.platform.mentor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "mentor_access")
public class MentorAccess {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, unique = true)
  private Long userId;

  @Column(nullable = false)
  private String status;

  @Column(nullable = false)
  private String source;

  @Column(name = "waitlisted_at", nullable = false)
  private Instant waitlistedAt;

  @Column(name = "activated_at")
  private Instant activatedAt;

  @Column(name = "batch_id")
  private Long batchId;

  @Column(name = "invite_code_id")
  private Long inviteCodeId;

  @Version
  private long version;

  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private Instant updatedAt;

  public static MentorAccess waitlisted(long userId) {
    MentorAccess access = new MentorAccess();
    access.userId = userId;
    access.status = "WAITLISTED";
    access.source = "SELF";
    access.waitlistedAt = Instant.now();
    return access;
  }

  public static MentorAccess active(long userId, String source) {
    MentorAccess access = new MentorAccess();
    access.userId = userId;
    access.status = "ACTIVE";
    access.source = source;
    access.waitlistedAt = Instant.now();
    access.activatedAt = Instant.now();
    return access;
  }

  public void activate(String newSource, Long newBatchId, Long newInviteCodeId, Instant at) {
    status = "ACTIVE";
    source = newSource;
    activatedAt = at;
    batchId = newBatchId;
    inviteCodeId = newInviteCodeId;
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getStatus() { return status; }
  public String getSource() { return source; }
  public Instant getWaitlistedAt() { return waitlistedAt; }
  public Instant getActivatedAt() { return activatedAt; }
  public Long getBatchId() { return batchId; }
  public Long getInviteCodeId() { return inviteCodeId; }
  public long getVersion() { return version; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
