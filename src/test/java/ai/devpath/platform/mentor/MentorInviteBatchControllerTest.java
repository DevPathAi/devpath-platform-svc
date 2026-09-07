package ai.devpath.platform.mentor;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.platform.auth.jwt.JwtService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MentorInviteBatchControllerTest {
  @Autowired MockMvc mvc;
  @Autowired JwtService jwt;
  @MockitoBean MentorInviteBatchService service;
  @MockitoBean MentorInviteBatchClaimRepository batches;

  @Test
  void adminCanRunOneDateButLearnerCannot() throws Exception {
    LocalDate date = LocalDate.parse("2026-09-05");
    when(service.run(date)).thenReturn(
        new MentorInviteBatchService.BatchRun(31L, date, true, 7));

    mvc.perform(post("/admin/mentor/invite-batches/2026-09-05/run")
            .header("Authorization", "Bearer " + jwt.mintAccessToken(99L, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.batchId").value(31))
        .andExpect(jsonPath("$.activatedCount").value(7));
    verify(service).run(date);

    mvc.perform(post("/admin/mentor/invite-batches/2026-09-05/run")
            .header("Authorization", "Bearer " + jwt.mintAccessToken(7L, "LEARNER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void inviteRoundsExposeOnlyAggregateSuccessfulDeliveryCountsWithoutAuthentication()
      throws Exception {
    when(batches.latestCompletedRounds(12)).thenReturn(List.of(
        new MentorInviteBatchClaimRepository.InviteRound(
            3L, LocalDate.parse("2026-09-05"), 7, Instant.parse("2026-09-05T02:01:00Z"))));

    mvc.perform(get("/mentor-access/invite-rounds"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].roundNumber").value(3))
        .andExpect(jsonPath("$[0].date").value("2026-09-05"))
        .andExpect(jsonPath("$[0].deliveredCount").value(7))
        .andExpect(jsonPath("$[0].lastSentAt").value("2026-09-05T02:01:00Z"))
        .andExpect(jsonPath("$[0].email").doesNotExist())
        .andExpect(jsonPath("$[0].pendingCount").doesNotExist());
  }
}
