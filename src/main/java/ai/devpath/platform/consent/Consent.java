package ai.devpath.platform.consent;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_consents")
public class Consent {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "user_id", nullable = false) private Long userId;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false) private ConsentType type;
	@Column(nullable = false) private String version;
	@Column(nullable = false) private boolean agreed;
	@Column(name = "agreed_at", nullable = false) private Instant agreedAt;
	@Column(name = "revoked_at") private Instant revokedAt;

	public Long getId() { return id; }
	public Long getUserId() { return userId; }
	public void setUserId(Long v) { this.userId = v; }
	public ConsentType getType() { return type; }
	public void setType(ConsentType v) { this.type = v; }
	public String getVersion() { return version; }
	public void setVersion(String v) { this.version = v; }
	public boolean isAgreed() { return agreed; }
	public void setAgreed(boolean v) { this.agreed = v; }
	public Instant getAgreedAt() { return agreedAt; }
	public void setAgreedAt(Instant v) { this.agreedAt = v; }
	public Instant getRevokedAt() { return revokedAt; }
	public void setRevokedAt(Instant v) { this.revokedAt = v; }
}
