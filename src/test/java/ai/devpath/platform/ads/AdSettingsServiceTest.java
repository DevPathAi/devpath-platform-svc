package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdSettingsServiceTest {

  @Autowired AdSettingsService service;

  @Test
  void defaultsToEnabledFromSeed() {
    assertThat(service.isEnabled()).isTrue();
  }

  @Test
  void setEnabledPersists() {
    service.setEnabled(false);
    assertThat(service.isEnabled()).isFalse();
  }
}
