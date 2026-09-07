package ai.devpath.platform.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MentorAccessConcurrencyTest {
  @Autowired MentorAccessService service;
  @Autowired UserRepository users;
  @Autowired JdbcClient jdbc;

  @Test
  void concurrentFirstLoginsCreateOneAccessAndOneWaitlistEvent() throws Exception {
    User user = saveUser();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<String> login = () -> {
        ready.countDown();
        start.await();
        return service.ensureForLogin(user).getStatus();
      };
      var futures = List.of(executor.submit(login), executor.submit(login));
      ready.await();
      start.countDown();

      assertThat(List.of(futures.get(0).get(), futures.get(1).get()))
          .containsExactly("WAITLISTED", "WAITLISTED");
    }

    assertThat(jdbc.sql("SELECT COUNT(*) FROM mentor_access WHERE user_id=:userId")
        .param("userId", user.getId()).query(Integer.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("""
        SELECT COUNT(*) FROM outbox
        WHERE aggregate_type='mentor_access' AND aggregate_id=:userId
          AND event_type='mentor.access.waitlisted'
        """)
        .param("userId", String.valueOf(user.getId()))
        .query(Integer.class).single()).isEqualTo(1);
  }

  private User saveUser() {
    User user = new User();
    user.setEmail("access-concurrency-" + System.nanoTime() + "@example.com");
    user.setNickname("테스트");
    user.setRole("LEARNER");
    user.setStatus("ACTIVE");
    user.setOnboardingStatus("DONE");
    return users.save(user);
  }
}
