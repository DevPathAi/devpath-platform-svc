package ai.devpath.platform.notification;

import ai.devpath.shared.event.UserRegisteredEvent;
import tools.jackson.databind.json.JsonMapper; // Boot 4 = Jackson 3
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class WelcomeNotificationConsumer {

	private static final Logger log = LoggerFactory.getLogger(WelcomeNotificationConsumer.class);
	private static final String TYPE = "WELCOME";
	private final NotificationRepository notifications;
	private final JsonMapper jsonMapper;

	public WelcomeNotificationConsumer(NotificationRepository notifications, JsonMapper jsonMapper) {
		this.notifications = notifications;
		this.jsonMapper = jsonMapper;
	}

	@KafkaListener(topics = UserRegisteredEvent.EVENT_TYPE, groupId = "devpath-platform")
	public void onUserRegistered(String payload) {
		UserRegisteredEvent event;
		try {
			event = jsonMapper.readValue(payload, UserRegisteredEvent.class);
		} catch (Exception e) {
			log.warn("UserRegisteredEvent 역직렬화 실패 — 메시지 skip: {}", payload, e);
			return; // poison 무한재시도 방지(다른 소비자와 동일 skip 전략)
		}
		if (notifications.existsByUserIdAndType(event.userId(), TYPE)) return; // 베스트에포트 멱등
		Notification n = new Notification();
		n.setUserId(event.userId());
		n.setType(TYPE);
		n.setTitle("환영합니다!");
		n.setBody("DevPath AI에 가입하신 것을 환영합니다. 진단을 시작해 보세요.");
		n.setCreatedAt(Instant.now());
		try {
			notifications.save(n);
		} catch (org.springframework.dao.DataIntegrityViolationException dup) {
			// P1-4: 동시 소비 레이스 — uq_notifications_welcome_user 위반 = 이미 생성됨. 무시(멱등).
		}
	}
}
