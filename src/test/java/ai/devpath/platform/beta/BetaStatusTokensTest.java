package ai.devpath.platform.beta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.devpath.platform.config.BetaProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class BetaStatusTokensTest {

    @Autowired StringRedisTemplate redis;

    private BetaStatusTokens store() {
        BetaProperties props = new BetaProperties();
        props.setStatusTtl(Duration.ofMinutes(30));
        return new BetaStatusTokens(redis, props);
    }

    @Test
    void issueThenValidate() {
        BetaStatusTokens s = store();
        String t = s.issue(42L);
        assertEquals(42L, s.validate(t).orElseThrow());
        assertFalse(s.validate("bogus-token").isPresent(), "무효 토큰은 empty");
    }
}
