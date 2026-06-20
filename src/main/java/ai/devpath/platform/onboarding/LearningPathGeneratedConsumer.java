package ai.devpath.platform.onboarding;

import ai.devpath.platform.user.UserRepository;
import ai.devpath.shared.event.LearningPathGeneratedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class LearningPathGeneratedConsumer {

  private static final Logger log = LoggerFactory.getLogger(LearningPathGeneratedConsumer.class);

  private final UserRepository users;
  private final JsonMapper jsonMapper;

  public LearningPathGeneratedConsumer(UserRepository users, JsonMapper jsonMapper) {
    this.users = users;
    this.jsonMapper = jsonMapper;
  }

  @KafkaListener(topics = LearningPathGeneratedEvent.EVENT_TYPE, groupId = "devpath-platform")
  @Transactional
  public void onLearningPathGenerated(String payload) {
    LearningPathGeneratedEvent event;
    try {
      event = jsonMapper.readValue(payload, LearningPathGeneratedEvent.class);
    } catch (Exception e) {
      log.warn("LearningPathGeneratedEvent 역직렬화 실패 — 메시지 skip: {}", payload, e);
      return;
    }
    int updated = users.markOnboardingDoneIfPathGenerated(event.userId());
    if (updated == 0) {
      log.debug("onboarding_status 무변동(userId={} 미존재 또는 PENDING/IN_PROGRESS 아님)",
          event.userId());
    }
  }
}
