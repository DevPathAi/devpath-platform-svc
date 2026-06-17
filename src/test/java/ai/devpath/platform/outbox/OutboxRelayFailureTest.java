package ai.devpath.platform.outbox;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class OutboxRelayFailureTest {

    @MockitoBean
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired OutboxRepository outbox;
    @Autowired OutboxRelay relay;

    @Test
    void relayKeepsPublishedAtNullWhenSendFails() {
        // Kafka send 실패 시뮬레이션: get()에서 예외를 던지는 CompletableFuture 반환
        CompletableFuture<Object> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unavailable"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn((CompletableFuture) failed);

        OutboxEntry e = new OutboxEntry();
        e.setAggregateType("user");
        e.setAggregateId("999");
        e.setEventType("user.user.registered");
        e.setPayload("{\"userId\":999}");
        e.setCreatedAt(Instant.now());
        Long id = outbox.save(e).getId();

        relay.relayOnce();

        assertNull(outbox.findById(id).orElseThrow().getPublishedAt(),
                "published_at must remain null when send fails");
    }
}
