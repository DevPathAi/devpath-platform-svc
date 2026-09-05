package ai.devpath.platform.mentor;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MentorAccessController.class)
@Import(MentorInviteCodeExceptionHandler.class)
class MentorAccessControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean MentorAccessService access;
  @MockitoBean MentorInviteCodeService codes;

  @Test
  void authenticatedUserCanReadStatusAndRedeemWithoutPuttingCodeInUrl() throws Exception {
    MentorAccess waiting = MentorAccess.waitlisted(7L);
    MentorAccess active = MentorAccess.active(7L, "INVITE_CODE");
    when(access.findForUser(7L)).thenReturn(waiting);
    when(codes.redeem(7L, "one-time-code")).thenReturn(active);

    mvc.perform(get("/mentor-access/me").with(jwt().jwt(jwt -> jwt.subject("7"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("WAITLISTED"));

    mvc.perform(post("/mentor-access/redeem")
            .with(jwt().jwt(jwt -> jwt.subject("7")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"one-time-code\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void inviteCodeFailureUsesStableNonSensitiveError() throws Exception {
    when(codes.redeem(7L, "raw-secret"))
        .thenThrow(new MentorInviteCodeException("INVITE_CODE_EXPIRED"));

    mvc.perform(post("/mentor-access/redeem")
            .with(jwt().jwt(jwt -> jwt.subject("7")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"raw-secret\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("INVITE_CODE_EXPIRED"))
        .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
            result.getResponse().getContentAsString()).doesNotContain("raw-secret"));
  }
}
