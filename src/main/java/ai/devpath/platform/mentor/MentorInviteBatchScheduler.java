package ai.devpath.platform.mentor;

import ai.devpath.platform.config.MentorAccessProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MentorInviteBatchScheduler {
  private final MentorInviteBatchService service;
  private final MentorAccessProperties properties;
  private final Clock clock;

  @Autowired
  public MentorInviteBatchScheduler(
      MentorInviteBatchService service, MentorAccessProperties properties) {
    this(service, properties, Clock.systemUTC());
  }

  MentorInviteBatchScheduler(
      MentorInviteBatchService service, MentorAccessProperties properties, Clock clock) {
    this.service = service;
    this.properties = properties;
    this.clock = clock;
  }

  @Scheduled(
      cron = "${devpath.mentor-access.batch-cron:0 0 10 * * *}",
      zone = "${devpath.mentor-access.batch-zone:Asia/Seoul}")
  void runDaily() {
    if (!properties.isBatchEnabled()) return;
    service.run(LocalDate.now(clock.withZone(ZoneId.of(properties.getBatchZone()))));
  }
}
