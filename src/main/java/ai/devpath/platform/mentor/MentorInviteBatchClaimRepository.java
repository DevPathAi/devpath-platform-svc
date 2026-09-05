package ai.devpath.platform.mentor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MentorInviteBatchClaimRepository {
  private final JdbcClient jdbc;

  public MentorInviteBatchClaimRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Long> claim(LocalDate batchDate, int chunkSize, int dailyCap) {
    return jdbc.sql("""
        INSERT INTO mentor_invite_batches(batch_date, status, chunk_size, daily_cap)
        VALUES (:batchDate, 'RUNNING', :chunkSize, :dailyCap)
        ON CONFLICT (batch_date) DO NOTHING
        RETURNING id
        """)
        .param("batchDate", batchDate)
        .param("chunkSize", chunkSize)
        .param("dailyCap", dailyCap)
        .query(Long.class)
        .optional();
  }

  public void complete(long batchId, int activatedCount, Instant completedAt) {
    int updated = jdbc.sql("""
        UPDATE mentor_invite_batches
        SET status = 'COMPLETED', activated_count = :activatedCount,
            completed_at = :completedAt, failure_reason = NULL
        WHERE id = :batchId AND status = 'RUNNING'
        """)
        .param("activatedCount", activatedCount)
        .param("completedAt", OffsetDateTime.ofInstant(completedAt, ZoneOffset.UTC))
        .param("batchId", batchId)
        .update();
    if (updated != 1) {
      throw new IllegalStateException("mentor invite batch is not running: " + batchId);
    }
  }

  public List<InviteRound> latestCompletedRounds(int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, 12));
    return jdbc.sql("""
        SELECT b.batch_date, COUNT(d.id) AS delivered_count, MAX(d.sent_at) AS last_sent_at,
               ROW_NUMBER() OVER (ORDER BY b.batch_date, b.id) AS round_number
        FROM mentor_invite_batches b
        JOIN mentor_invite_deliveries d ON d.batch_id = b.id
        WHERE b.status = 'COMPLETED'
        GROUP BY b.id, b.batch_date
        ORDER BY b.batch_date DESC, b.id DESC
        LIMIT :limit
        """)
        .param("limit", boundedLimit)
        .query((rs, rowNum) -> new InviteRound(
            rs.getLong("round_number"),
            rs.getObject("batch_date", LocalDate.class),
            rs.getInt("delivered_count"),
            rs.getTimestamp("last_sent_at").toInstant()))
        .list();
  }

  public record InviteRound(
      long roundNumber, LocalDate date, int deliveredCount, Instant lastSentAt) {}
}
