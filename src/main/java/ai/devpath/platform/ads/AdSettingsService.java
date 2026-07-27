package ai.devpath.platform.ads;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 전역 광고 on/off. 단일행(id=1)을 읽고 쓴다. */
@Service
public class AdSettingsService {

  private static final int SINGLETON_ID = 1;
  private final AdSettingsRepository repo;

  public AdSettingsService(AdSettingsRepository repo) {
    this.repo = repo;
  }

  @Transactional(readOnly = true)
  public boolean isEnabled() {
    return repo.findById(SINGLETON_ID).map(AdSettings::isEnabled).orElse(false);
  }

  @Transactional
  public void setEnabled(boolean enabled) {
    AdSettings s = repo.findById(SINGLETON_ID).orElseGet(() -> {
      AdSettings created = new AdSettings();
      created.setId(SINGLETON_ID);
      return created;
    });
    s.setEnabled(enabled);
    repo.save(s);
  }
}
