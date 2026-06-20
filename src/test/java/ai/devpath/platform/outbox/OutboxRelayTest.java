package ai.devpath.platform.outbox;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"user.user.registered"}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class OutboxRelayTest {

    @Autowired OutboxRepository outbox;
    @Autowired OutboxRelay relay;
    @Autowired ConsumerFactory<String, String> cf;
    @Autowired EmbeddedKafkaBroker broker;

    @Test
    void relayPublishesUnpublishedRowAndMarksPublished() {
        OutboxEntry e = new OutboxEntry();
        e.setAggregateType("user");
        e.setAggregateId("777");
        e.setEventType("user.user.registered");
        e.setPayload("{\"userId\":777}");
        e.setCreatedAt(Instant.now());
        Long id = outbox.save(e).getId();

        int published = relay.relayOnce();
        assertTrue(published >= 1);
        assertTrue(outbox.findById(id).orElseThrow().getPublishedAt() != null, "published_at 설정");

        // 발행된 레코드 중 하나 이상에 "777" 포함 검증
        // (스케줄러 중복 발행 가능성 있어 getSingleRecord 대신 getRecords 사용)
        try (Consumer<String, String> c = cf.createConsumer("t-grp", "t")) {
            broker.consumeFromAnEmbeddedTopic(c, "user.user.registered");
            ConsumerRecords<String, String> recs = KafkaTestUtils.getRecords(c);
            boolean found = StreamSupport.stream(recs.spliterator(), false)
                    .anyMatch(r -> r.value().contains("777"));
            assertTrue(found, "발행된 레코드에 userId=777 포함");
        }
    }
}
