package ai.devpath.platform.outbox;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelay {

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka) {
        this.outbox = outbox;
        this.kafka = kafka;
    }

    // P0-3: Kafka send는 비동기다. future 성공을 확인한 뒤에만 published_at을 설정한다.
    // 발행 실패 시 해당 행은 미발행 유지(다음 폴링 재시도) + 순서 보장 위해 중단. @Transactional 미사용
    // (각 save 자동 커밋 → 실패 전 성공분은 published로 확정, Kafka를 DB 트랜잭션에 묶지 않음).
    public int relayOnce() {
        var batch = outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        int count = 0;
        for (OutboxEntry e : batch) {
            try {
                kafka.send(e.getEventType(), e.getAggregateId(), e.getPayload())
                        .get(5, TimeUnit.SECONDS); // 발행 성공 대기
            } catch (Exception ex) {
                break; // 발행 실패 → published_at 미설정, 이후 행은 다음 주기로
            }
            e.setPublishedAt(Instant.now());
            outbox.save(e);
            count++;
        }
        return count;
    }
}
