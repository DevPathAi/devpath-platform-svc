package ai.devpath.platform.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicSupportControllerTest {

  @Test
  void createsAnonymousRequestUsingConnectionAddress() throws Exception {
    PublicSupportService service = mock(PublicSupportService.class);
    SupportRequest saved = mock(SupportRequest.class);
    when(saved.getId()).thenReturn(91L);
    when(service.create(any(), any())).thenReturn(saved);
    var mvc = MockMvcBuilders.standaloneSetup(new PublicSupportController(service))
        .setControllerAdvice(new PublicSupportExceptionHandler())
        .build();

    mvc.perform(post("/support/public-requests")
            .with(request -> { request.setRemoteAddr("203.0.113.10"); return request; })
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"type":"ERROR","email":"person@example.com","title":"오류", "body":"본문",
                 "privacyConsent":true,"turnstileToken":"browser-token"}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(91));

    verify(service).create(any(), org.mockito.ArgumentMatchers.eq("203.0.113.10"));
  }

  @Test
  void exposesStableErrorCodesWithoutSensitiveDetails() throws Exception {
    for (var scenario : java.util.Map.of(
        "TURNSTILE_FAILED", 422,
        "TURNSTILE_UNAVAILABLE", 503,
        "RATE_LIMITED", 429).entrySet()) {
      PublicSupportService service = mock(PublicSupportService.class);
      when(service.create(any(), any()))
          .thenThrow(new PublicSupportException(scenario.getKey(), "internal provider detail"));
      var mvc = MockMvcBuilders.standaloneSetup(new PublicSupportController(service))
          .setControllerAdvice(new PublicSupportExceptionHandler())
          .build();

      mvc.perform(post("/support/public-requests")
              .contentType(MediaType.APPLICATION_JSON)
              .content("""
                  {"type":"ERROR","email":"person@example.com","title":"오류", "body":"본문",
                   "privacyConsent":true,"turnstileToken":"browser-token"}
                  """))
          .andExpect(status().is(scenario.getValue()))
          .andExpect(jsonPath("$.code").value(scenario.getKey()))
          .andExpect(jsonPath("$.message").doesNotExist());
    }
  }

  @Test
  void invalidPublicPayloadReturnsStable400WithoutValidationDetails() throws Exception {
    PublicSupportService service = mock(PublicSupportService.class);
    when(service.create(any(), any()))
        .thenThrow(new IllegalArgumentException("email contained secret@example.com"));
    var mvc = MockMvcBuilders.standaloneSetup(new PublicSupportController(service))
        .setControllerAdvice(new PublicSupportExceptionHandler())
        .build();

    mvc.perform(post("/support/public-requests")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"type":"ERROR","email":"bad","title":"오류", "body":"본문",
                 "privacyConsent":true,"turnstileToken":"browser-token"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").doesNotExist());
  }
}
