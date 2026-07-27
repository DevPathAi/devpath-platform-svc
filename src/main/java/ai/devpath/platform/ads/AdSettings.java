package ai.devpath.platform.ads;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ad_settings")
public class AdSettings {
  @Id
  private Integer id;
  private boolean enabled;

  public Integer getId() { return id; }
  public void setId(Integer v) { this.id = v; }
  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean v) { this.enabled = v; }
}
