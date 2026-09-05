package ai.devpath.platform.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "devpath.mentor-access.batch-chunk-size=2",
    "devpath.mentor-access.batch-daily-cap=3"
})
@ActiveProfiles("test")
class MentorInviteBatchConcurrencyTest {
  private static final LocalDate BATCH_DATE = LocalDate.parse("2099-12-30");

  @Autowired MentorInviteBatchService service;
  @Autowired MentorInviteBatchClaimRepository batches;
  @Autowired MentorAccessRepository accesses;
  @Autowired UserRepository users;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void cleanMentorBatchState() {
    jdbc.sql("DELETE FROM mentor_invite_deliveries").update();
    jdbc.sql("UPDATE mentor_access SET batch_id = NULL").update();
    jdbc.sql("DELETE FROM mentor_invite_batches").update();
    jdbc.sql("DELETE FROM mentor_access").update();
    jdbc.sql("DELETE FROM outbox WHERE event_type LIKE 'mentor.%'").update();
  }

  @Test
  void twoRunnersClaimOneDailyBatchWithoutDuplicateActivationEvents() throws Exception {
    for (int index = 0; index < 3; index++) {
      User user = saveUser("batch-user-" + index + "-");
      accesses.save(MentorAccess.waitlisted(user.getId()));
    }

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<MentorInviteBatchService.BatchRun> run = () -> {
        ready.countDown();
        start.await();
        return service.run(BATCH_DATE);
      };
      var futures = List.of(executor.submit(run), executor.submit(run));
      ready.await();
      start.countDown();
      List<MentorInviteBatchService.BatchRun> results =
          List.of(futures.get(0).get(), futures.get(1).get());

      assertThat(results).extracting(MentorInviteBatchService.BatchRun::claimed)
          .containsExactlyInAnyOrder(true, false);
      assertThat(results).extracting(MentorInviteBatchService.BatchRun::activatedCount)
          .containsExactlyInAnyOrder(3, 0);
    }

    assertThat(jdbc.sql("SELECT COUNT(*) FROM mentor_invite_batches WHERE batch_date=:date")
        .param("date", BATCH_DATE).query(Integer.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM mentor_access WHERE status='ACTIVE'")
        .query(Integer.class).single()).isEqualTo(3);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox WHERE event_type='mentor.access.activated'")
        .query(Integer.class).single()).isEqualTo(3);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox WHERE event_type='mentor.invite_batch.completed'")
        .query(Integer.class).single()).isEqualTo(1);
  }

  @Test
  void latestCompletedRoundsUsesSuccessfulDeliveryRowsAndStableRoundNumbers() {
    User first = saveUser("round-first-");
    User second = saveUser("round-second-");
    long firstBatch = insertCompletedBatch(LocalDate.parse("2099-12-27"));
    long secondBatch = insertCompletedBatch(LocalDate.parse("2099-12-28"));
    long thirdBatch = insertCompletedBatch(LocalDate.parse("2099-12-29"));

    insertDelivery(firstBatch, first.getId());
    insertDelivery(secondBatch, first.getId());
    insertDelivery(secondBatch, second.getId());
    insertDelivery(thirdBatch, second.getId());

    assertThat(batches.latestCompletedRounds(2))
        .extracting(
            MentorInviteBatchClaimRepository.InviteRound::roundNumber,
            MentorInviteBatchClaimRepository.InviteRound::date,
            MentorInviteBatchClaimRepository.InviteRound::deliveredCount)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(3L, LocalDate.parse("2099-12-29"), 1),
            org.assertj.core.groups.Tuple.tuple(2L, LocalDate.parse("2099-12-28"), 2));
  }

  private long insertCompletedBatch(LocalDate date) {
    return jdbc.sql("""
        INSERT INTO mentor_invite_batches(
          batch_date,status,chunk_size,daily_cap,activated_count,completed_at)
        VALUES (:date,'COMPLETED',25,100,1,now())
        RETURNING id
        """)
        .param("date", date)
        .query(Long.class)
        .single();
  }

  private void insertDelivery(long batchId, long userId) {
    jdbc.sql("""
        INSERT INTO mentor_invite_deliveries(
          event_id,user_id,batch_id,provider_message_id,sent_at)
        VALUES (:eventId,:userId,:batchId,:providerMessageId,now())
        """)
        .param("eventId", java.util.UUID.randomUUID())
        .param("userId", userId)
        .param("batchId", batchId)
        .param("providerMessageId", "test:" + java.util.UUID.randomUUID())
        .update();
  }

  private User saveUser(String prefix) {
    User user = new User();
    user.setEmail(prefix + System.nanoTime() + "@example.com");
    user.setNickname("테스트");
    user.setRole("LEARNER");
    user.setStatus("ACTIVE");
    user.setOnboardingStatus("DONE");
    return users.save(user);
  }
}
