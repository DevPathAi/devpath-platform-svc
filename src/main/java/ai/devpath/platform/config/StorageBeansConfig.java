package ai.devpath.platform.config;

import ai.devpath.shared.storage.StorageProperties;
import ai.devpath.shared.storage.StoredFileValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** avatar 업로드 검증기. shared storage와 동일하게 endpoint 설정 시에만 등록한다. */
@Configuration
@ConditionalOnProperty("devpath.storage.endpoint")
public class StorageBeansConfig {

  @Bean
  public StoredFileValidator storedFileValidator(StorageProperties props) {
    return new StoredFileValidator(props.getAllowedContentTypes(), props.getMaxFileSize());
  }
}
