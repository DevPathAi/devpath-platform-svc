package ai.devpath.platform.beta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class BetaAllowlistRepositoryTest {

    @Autowired BetaAllowlistRepository repo;

    @Test
    void existsByEmail_reflectsSavedRows() {
        BetaAllowlist row = new BetaAllowlist();
        row.setEmail("beta@devpath.ai");
        repo.save(row);
        assertThat(repo.existsByEmail("beta@devpath.ai")).isTrue();
        assertThat(repo.existsByEmail("nope@devpath.ai")).isFalse();
    }
}
