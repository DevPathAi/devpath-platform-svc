package ai.devpath.platform.ads;

import java.util.Random;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdsConfig {
  // java.util.Random은 내부적으로 AtomicLong을 사용하므로 스레드 안전(thread-safe)하다.
  // 따라서 싱글턴 빈으로 공유해도 동시 광고 서빙에 안전하다.
  @Bean
  public Random adRandom() {
    return new Random();
  }
}
