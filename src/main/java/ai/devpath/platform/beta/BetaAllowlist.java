package ai.devpath.platform.beta;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "beta_allowlist")
public class BetaAllowlist {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true) private String email;
    private String note;
    @Column(name = "added_by") private String addedBy;
    @Column(name = "created_at", insertable = false, updatable = false) private Instant createdAt;

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public String getAddedBy() { return addedBy; }
    public void setAddedBy(String v) { this.addedBy = v; }
    public Instant getCreatedAt() { return createdAt; }
}
