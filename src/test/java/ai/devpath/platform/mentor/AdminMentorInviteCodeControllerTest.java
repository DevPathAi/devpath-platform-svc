package ai.devpath.platform.mentor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.platform.auth.jwt.JwtService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminMentorInviteCodeControllerTest {
  @Autowired MockMvc mvc;
  @Autowired JwtService jwt;
  @MockitoBean MentorInviteCodeService service;

  @Test
  void adminCanCreateOneTimeVisibleCodeButLearnerCannot() throws Exception {
    when(service.create(any(), eq(99L))).thenReturn(
        new MentorInviteCodeService.IssuedCode(
            11L, "one-time-raw-code", Instant.parse("2026-09-06T00:00:00Z"), 5));
    String body = """
        {"label":"2기 심사","audience":"JUDGE","cohort":"cohort-2",
         "expiresAt":"2026-09-06T00:00:00Z","maxRedemptions":5}
        """;

    mvc.perform(post("/admin/mentor/invite-codes")
            .header("Authorization", "Bearer " + jwt.mintAccessToken(99L, "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(11))
        .andExpect(jsonPath("$.code").value("one-time-raw-code"));

    mvc.perform(post("/admin/mentor/invite-codes")
            .header("Authorization", "Bearer " + jwt.mintAccessToken(7L, "LEARNER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminCanDisableCodeWithAnAuditReason() throws Exception {
    mvc.perform(post("/admin/mentor/invite-codes/11/disable")
            .header("Authorization", "Bearer " + jwt.mintAccessToken(99L, "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"심사 종료\"}"))
        .andExpect(status().isNoContent());

    verify(service).disable(11L, 99L, "심사 종료");
  }
}
