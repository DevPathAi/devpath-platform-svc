package ai.devpath.platform.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_oauth_identities")
public class UserOauthIdentity {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "user_id", nullable = false) private Long userId;
	@Column(nullable = false) private String provider;
	@Column(name = "provider_user_id", nullable = false) private String providerUserId;
	@Column(name = "access_token_encrypted") private String accessTokenEncrypted;
	@Column(name = "refresh_token_encrypted") private String refreshTokenEncrypted;
	private String scope;
	@Column(name = "linked_at", nullable = false) private Instant linkedAt;

	public Long getId() { return id; }
	public Long getUserId() { return userId; }
	public void setUserId(Long v) { this.userId = v; }
	public String getProvider() { return provider; }
	public void setProvider(String v) { this.provider = v; }
	public String getProviderUserId() { return providerUserId; }
	public void setProviderUserId(String v) { this.providerUserId = v; }
	public String getAccessTokenEncrypted() { return accessTokenEncrypted; }
	public void setAccessTokenEncrypted(String v) { this.accessTokenEncrypted = v; }
	public String getRefreshTokenEncrypted() { return refreshTokenEncrypted; }
	public void setRefreshTokenEncrypted(String v) { this.refreshTokenEncrypted = v; }
	public String getScope() { return scope; }
	public void setScope(String v) { this.scope = v; }
	public Instant getLinkedAt() { return linkedAt; }
	public void setLinkedAt(Instant v) { this.linkedAt = v; }
}
