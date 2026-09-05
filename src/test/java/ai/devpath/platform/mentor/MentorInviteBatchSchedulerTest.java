package ai.devpath.platform.mentor;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

import ai.devpath.platform.config.MentorAccessProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MentorInviteBatchSchedulerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-05T01:00:00Z"), ZoneOffset.UTC);

  @Test
  void runsForConfiguredKoreanCalendarDateOnlyWhenEnabled() {
    MentorInviteBatchService service = mock(MentorInviteBatchService.class);
    MentorAccessProperties properties = new MentorAccessProperties();
    properties.setBatchEnabled(true);
    properties.setBatchZone("Asia/Seoul");

    new MentorInviteBatchScheduler(service, properties, CLOCK).runDaily();

    verify(service).run(LocalDate.parse("2026-09-05"));
  }

  @Test
  void disabledSchedulerDoesNothing() {
    MentorInviteBatchService service = mock(MentorInviteBatchService.class);
    MentorAccessProperties properties = new MentorAccessProperties();

    new MentorInviteBatchScheduler(service, properties, CLOCK).runDaily();

    verify(service, never()).run(org.mockito.ArgumentMatchers.any());
  }
}
