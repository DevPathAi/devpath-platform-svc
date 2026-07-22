package ai.devpath.platform.ads;

import java.util.Random;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdsConfig {
  @Bean
  public Random adRandom() {
    return new Random();
  }
}
