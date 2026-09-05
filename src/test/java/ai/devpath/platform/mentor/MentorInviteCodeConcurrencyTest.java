package ai.devpath.platform.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MentorInviteCodeConcurrencyTest {
  @Autowired MentorInviteCodeService service;
  @Autowired MentorInviteCodeRepository codes;
  @Autowired MentorInviteCodeRedemptionRepository redemptions;
  @Autowired MentorAccessRepository accesses;
  @Autowired UserRepository users;

  @Test
  void oneRemainingRedemptionActivatesExactlyOneConcurrentUser() throws Exception {
    User admin = saveUser("code-admin-", "ADMIN");
    User first = saveUser("code-first-", "LEARNER");
    User second = saveUser("code-second-", "LEARNER");
    accesses.save(MentorAccess.waitlisted(first.getId()));
    accesses.save(MentorAccess.waitlisted(second.getId()));
    MentorInviteCodeService.IssuedCode issued = service.create(
        new MentorInviteCodeService.CreateCommand(
            "concurrency", "JUDGE", "cohort-test", Instant.now().plusSeconds(3600), 1),
        admin.getId());

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<String> redeemFirst = () -> redeemAfterBarrier(first.getId(), issued.code(), ready, start);
      Callable<String> redeemSecond = () -> redeemAfterBarrier(second.getId(), issued.code(), ready, start);
      var futures = List.of(executor.submit(redeemFirst), executor.submit(redeemSecond));
      ready.await();
      start.countDown();
      List<String> results = List.of(futures.get(0).get(), futures.get(1).get());

      assertThat(results).containsExactlyInAnyOrder("ACTIVE", "INVITE_CODE_EXHAUSTED");
    }

    MentorInviteCode stored = codes.findById(issued.id()).orElseThrow();
    assertThat(stored.getRedemptionCount()).isEqualTo(1);
    assertThat(redemptions.countByInviteCodeId(issued.id())).isEqualTo(1);
  }

  private String redeemAfterBarrier(
      long userId,
      String code,
      CountDownLatch ready,
      CountDownLatch start) throws Exception {
    ready.countDown();
    start.await();
    try {
      return service.redeem(userId, code).getStatus();
    } catch (MentorInviteCodeException exception) {
      return exception.getCode();
    }
  }

  private User saveUser(String prefix, String role) {
    User user = new User();
    user.setEmail(prefix + System.nanoTime() + "@example.com");
    user.setNickname("테스트");
    user.setRole(role);
    user.setStatus("ACTIVE");
    user.setOnboardingStatus("DONE");
    return users.save(user);
  }
}
