package ai.devpath.platform.ads;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "advertisement")
public class Advertisement {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String title;

  @Column(name = "image_url")
  private String imageUrl;

  @Column(name = "link_url")
  private String linkUrl;

  private String slot;
  private int weight = 1;
  private String status = "ACTIVE";

  @Column(name = "starts_at")
  private Instant startsAt;

  @Column(name = "ends_at")
  private Instant endsAt;

  public Long getId() { return id; }
  public String getTitle() { return title; }
  public void setTitle(String v) { this.title = v; }
  public String getImageUrl() { return imageUrl; }
  public void setImageUrl(String v) { this.imageUrl = v; }
  public String getLinkUrl() { return linkUrl; }
  public void setLinkUrl(String v) { this.linkUrl = v; }
  public String getSlot() { return slot; }
  public void setSlot(String v) { this.slot = v; }
  public int getWeight() { return weight; }
  public void setWeight(int v) { this.weight = v; }
  public String getStatus() { return status; }
  public void setStatus(String v) { this.status = v; }
  public Instant getStartsAt() { return startsAt; }
  public void setStartsAt(Instant v) { this.startsAt = v; }
  public Instant getEndsAt() { return endsAt; }
  public void setEndsAt(Instant v) { this.endsAt = v; }
}
