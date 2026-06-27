package ai.devpath.platform.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 사용자별 FCM 디바이스 토큰(타깃 푸시 발송용). 하드닝 트랙 C.
 * 스키마: devpath-shared {@code V202606271001__device_tokens.sql}.
 */
@Entity
@Table(name = "device_tokens")
public class DeviceToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(nullable = false)
	private String token;

	@Column(nullable = false)
	private String platform;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	// 서비스가 매 upsert마다 갱신(insert/update 모두 코드에서 설정).
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public Long getId() { return id; }
	public Long getUserId() { return userId; }
	public void setUserId(Long v) { this.userId = v; }
	public String getToken() { return token; }
	public void setToken(String v) { this.token = v; }
	public String getPlatform() { return platform; }
	public void setPlatform(String v) { this.platform = v; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
