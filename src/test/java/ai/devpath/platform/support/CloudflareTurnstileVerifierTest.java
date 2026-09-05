package ai.devpath.platform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ai.devpath.platform.config.PublicSupportProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CloudflareTurnstileVerifierTest {
  private RestClient.Builder builder;
  private MockRestServiceServer server;
  private PublicSupportProperties properties;

  @BeforeEach
  void setUp() {
    builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    properties = new PublicSupportProperties();
    properties.setTurnstileSecret("server-secret");
    properties.setTurnstileVerifyUrl("https://turnstile.example.test/siteverify");
    properties.setTurnstileAction("public_support");
    properties.setTurnstileHostnames(java.util.List.of("leva.ai.kr", "localhost"));
    properties.setVerificationTimeout(Duration.ofSeconds(1));
  }

  @Test
  void acceptsSuccessOnlyForExpectedActionAndHostname() {
    server.expect(once(), requestTo("https://turnstile.example.test/siteverify"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
        .andExpect(content().string(org.hamcrest.Matchers.allOf(
            org.hamcrest.Matchers.containsString("secret=server-secret"),
            org.hamcrest.Matchers.containsString("response=browser-token"),
            org.hamcrest.Matchers.containsString("remoteip=203.0.113.10"))))
        .andRespond(withSuccess("""
            {"success":true,"hostname":"leva.ai.kr","action":"public_support"}
            """, MediaType.APPLICATION_JSON));

    assertThat(verifier().verify("browser-token", "203.0.113.10")).isTrue();
    server.verify();
  }

  @Test
  void rejectsProviderFailureWrongActionAndWrongHostname() {
    var responses = java.util.List.of(
        "{\"success\":false,\"hostname\":\"leva.ai.kr\",\"action\":\"public_support\"}",
        "{\"success\":true,\"hostname\":\"leva.ai.kr\",\"action\":\"login\"}",
        "{\"success\":true,\"hostname\":\"evil.example\",\"action\":\"public_support\"}");
    for (String response : responses) {
      server.expect(once(), requestTo("https://turnstile.example.test/siteverify"))
          .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
    for (String ignored : responses) {
      assertThat(verifier().verify("browser-token", "203.0.113.10")).isFalse();
    }
    server.verify();
  }

  @Test
  void failsClosedWhenProviderIsUnavailable() {
    server.expect(once(), requestTo("https://turnstile.example.test/siteverify"))
        .andRespond(withServerError());
    assertThatThrownBy(() -> verifier().verify("browser-token", "203.0.113.10"))
        .isInstanceOf(TurnstileUnavailableException.class);
  }

  private CloudflareTurnstileVerifier verifier() {
    return new CloudflareTurnstileVerifier(builder.build(), properties);
  }
}
