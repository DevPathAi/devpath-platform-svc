package ai.devpath.platform.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private String email;
	private String nickname;
	@Column(nullable = false) private String role = "LEARNER";
	@Column(nullable = false) private String status = "ACTIVE";
	@Column(name = "onboarding_status", nullable = false) private String onboardingStatus = "PENDING";
	@Column(name = "created_at", insertable = false, updatable = false) private Instant createdAt;
	@Column(name = "updated_at", insertable = false, updatable = false) private Instant updatedAt;
	@Column(name = "last_active_at", insertable = false, updatable = false) private Instant lastActiveAt;

	public Long getId() { return id; }
	public String getEmail() { return email; }
	public void setEmail(String v) { this.email = v; }
	public String getNickname() { return nickname; }
	public void setNickname(String v) { this.nickname = v; }
	public String getRole() { return role; }
	public void setRole(String v) { this.role = v; }
	public String getStatus() { return status; }
	public void setStatus(String v) { this.status = v; }
	public String getOnboardingStatus() { return onboardingStatus; }
	public void setOnboardingStatus(String v) { this.onboardingStatus = v; }
	public Instant getCreatedAt() { return createdAt; }
}
