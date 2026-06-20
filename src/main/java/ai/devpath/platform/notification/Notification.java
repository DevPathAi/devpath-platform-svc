package ai.devpath.platform.notification;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "notifications")
public class Notification {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
	@Column(name = "user_id", nullable = false) private Long userId;
	@Column(nullable = false) private String type;
	@Column(nullable = false) private String title;
	private String body;
	@Column(name = "read_at") private Instant readAt;
	@Column(name = "created_at", nullable = false) private Instant createdAt;

	public Long getId() { return id; }
	public Long getUserId() { return userId; }
	public void setUserId(Long v) { this.userId = v; }
	public String getType() { return type; }
	public void setType(String v) { this.type = v; }
	public String getTitle() { return title; }
	public void setTitle(String v) { this.title = v; }
	public String getBody() { return body; }
	public void setBody(String v) { this.body = v; }
	public Instant getReadAt() { return readAt; }
	public void setReadAt(Instant v) { this.readAt = v; }
	public Instant getCreatedAt() { return createdAt; }
	public void setCreatedAt(Instant v) { this.createdAt = v; }
}
