package ai.devpath.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.shared.storage.StorageAutoConfiguration;
import ai.devpath.shared.storage.StoredFileValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StorageBeansConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class))
          .withUserConfiguration(StorageBeansConfig.class);

  @Test
  void registersValidatorWhenEndpointSet() {
    runner
        .withPropertyValues(
            "devpath.storage.endpoint=http://minio:9000",
            "devpath.storage.bucket=devpath",
            "devpath.storage.access-key=k",
            "devpath.storage.secret-key=s",
            "devpath.storage.public-base-url=http://minio:9000")
        .run(ctx -> assertThat(ctx).hasSingleBean(StoredFileValidator.class));
  }

  @Test
  void noValidatorWhenEndpointMissing() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean(StoredFileValidator.class));
  }
}
