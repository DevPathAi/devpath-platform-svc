package ai.devpath.platform.onboarding;

import ai.devpath.platform.user.UserRepository;
import ai.devpath.shared.event.AssessmentCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AssessmentCompletedConsumer {

  private static final Logger log = LoggerFactory.getLogger(AssessmentCompletedConsumer.class);

  private final UserRepository users;
  private final JsonMapper jsonMapper;

  public AssessmentCompletedConsumer(UserRepository users, JsonMapper jsonMapper) {
    this.users = users;
    this.jsonMapper = jsonMapper;
  }

  @KafkaListener(topics = AssessmentCompletedEvent.EVENT_TYPE, groupId = "devpath-platform")
  @Transactional
  public void onAssessmentCompleted(String payload) {
    AssessmentCompletedEvent event;
    try {
      event = jsonMapper.readValue(payload, AssessmentCompletedEvent.class);
    } catch (Exception e) {
      log.warn("AssessmentCompletedEvent 역직렬화 실패 — 메시지 skip: {}", payload, e);
      return; // poison 무한재시도 방지(커밋 후 진행)
    }
    int updated = users.markAssessmentStartedIfPending(event.userId());
    if (updated == 0) {
      log.debug("onboarding_status 무변동(userId={} 미존재 또는 PENDING 아님)", event.userId());
    }
  }
}
