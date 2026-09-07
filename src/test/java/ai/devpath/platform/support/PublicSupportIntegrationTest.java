package ai.devpath.platform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "server.forward-headers-strategy=framework")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicSupportIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired SupportRequestRepository requests;
  @MockitoBean TurnstileVerifier turnstile;
  @MockitoBean PublicSupportRateLimiter rateLimiter;

  @BeforeEach
  void allowRequest() {
    when(turnstile.verify(any(), any())).thenReturn(true);
    when(rateLimiter.allow(any(), any())).thenReturn(true);
  }

  @Test
  void anonymousHomeRequestJoinsExistingQueueAndIsMasked() throws Exception {
    String response = mvc.perform(post("/support/public-requests")
            .with(request -> { request.setRemoteAddr("203.0.113.10"); return request; })
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"type":"ERROR","email":" Person@Example.com ","title":"로그인 오류",
                 "body":"person@example.com, token=secret-value", "privacyConsent":true,
                 "turnstileToken":"browser-token"}
                """.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNumber())
        .andReturn().getResponse().getContentAsString();

    long id = Long.parseLong(response.replaceAll("\\D+", ""));
    SupportRequest saved = requests.findById(id).orElseThrow();
    assertThat(saved.getReporterId()).isNull();
    assertThat(saved.getSource()).isEqualTo("PUBLIC_HOME");
    assertThat(saved.getContactEmail()).isEqualTo("person@example.com");
    assertThat(saved.getPrivacyConsentAt()).isNotNull();
    assertThat(saved.getBody()).isEqualTo("[EMAIL], token=[REDACTED]");
    assertThat(saved.getBody()).doesNotContain("203.0.113.10", "browser-token", "secret-value");
  }

  @Test
  void turnstileFailureReturnsStable422AndDoesNotStore() throws Exception {
    when(turnstile.verify(any(), any())).thenReturn(false);
    long before = requests.count();

    mvc.perform(post("/support/public-requests")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validJson()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("TURNSTILE_FAILED"));
    assertThat(requests.count()).isEqualTo(before);
  }

  @Test
  void allowsHomeCorsPreflightButRejectsUnknownOrigin() throws Exception {
    mvc.perform(options("/support/public-requests")
            .header(HttpHeaders.ORIGIN, "https://leva.ai.kr")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://leva.ai.kr"));

    mvc.perform(options("/support/public-requests")
            .header(HttpHeaders.ORIGIN, "https://evil.example")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
        .andExpect(status().isForbidden());
  }

  @Test
  void rejectsUnknownOriginAndNonJsonPostsBeforeStorage() throws Exception {
    long before = requests.count();

    mvc.perform(post("/support/public-requests")
            .header(HttpHeaders.ORIGIN, "https://evil.example")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validJson()))
        .andExpect(status().isForbidden());

    mvc.perform(post("/support/public-requests")
            .header(HttpHeaders.ORIGIN, "https://leva.ai.kr")
            .contentType(MediaType.TEXT_PLAIN)
            .content(validJson()))
        .andExpect(status().isUnsupportedMediaType());

    assertThat(requests.count()).isEqualTo(before);
  }

  @Test
  void usesTrustedProxyForwardedClientAddressForAbuseChecks() throws Exception {
    mvc.perform(post("/support/public-requests")
            .with(request -> { request.setRemoteAddr("10.42.0.17"); return request; })
            .header("X-Forwarded-For", "203.0.113.22")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validJson()))
        .andExpect(status().isCreated());

    verify(turnstile).verify("browser-token", "203.0.113.22");
    verify(rateLimiter).allow("203.0.113.22", "person@example.com");
  }

  private static String validJson() {
    return """
        {"type":"INQUIRY","email":"person@example.com","title":"문의","body":"본문",
         "privacyConsent":true,"turnstileToken":"browser-token"}
        """;
  }
}
