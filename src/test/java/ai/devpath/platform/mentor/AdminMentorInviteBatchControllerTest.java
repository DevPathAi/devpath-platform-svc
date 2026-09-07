package ai.devpath.platform.mentor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminMentorInviteBatchControllerTest {

  @Test
  void disabledBatchReturnsStableServiceUnavailableResponse() throws Exception {
    MentorInviteBatchService service = mock(MentorInviteBatchService.class);
    when(service.run(any())).thenThrow(new MentorInviteCodeException("MENTOR_BATCH_DISABLED"));
    var mvc = MockMvcBuilders.standaloneSetup(new AdminMentorInviteBatchController(service))
        .setControllerAdvice(new MentorInviteCodeExceptionHandler())
        .build();

    mvc.perform(post("/admin/mentor/invite-batches/2026-09-05/run"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("MENTOR_BATCH_DISABLED"));
  }
}
