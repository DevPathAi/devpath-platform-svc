package ai.devpath.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

class FlywayTestProfilePropertiesTest {

	@Test
	void disablesTransactionalLockForNonTransactionalMigrations() throws IOException {
		var propertySources = new MutablePropertySources();
		new YamlPropertySourceLoader()
				.load("test", new ClassPathResource("application-test.yml"))
				.forEach(propertySources::addLast);

		var flywayProperties = new Binder(ConfigurationPropertySources.from(propertySources))
				.bind("spring.flyway", Bindable.of(FlywayProperties.class))
				.orElseThrow(IllegalStateException::new);

		assertThat(flywayProperties.getPostgresql().getTransactionalLock()).isFalse();
	}
}
