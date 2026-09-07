package ai.devpath.platform.support;

import ai.devpath.platform.config.PublicSupportProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.http.HttpClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class CloudflareTurnstileVerifier implements TurnstileVerifier {
  private final RestClient client;
  private final PublicSupportProperties properties;

  @Autowired
  public CloudflareTurnstileVerifier(
      RestClient.Builder builder,
      PublicSupportProperties properties) {
    this(configuredClient(builder, properties), properties);
  }

  CloudflareTurnstileVerifier(RestClient client, PublicSupportProperties properties) {
    if (properties.getTurnstileSecret() == null || properties.getTurnstileSecret().isBlank()) {
      throw new IllegalStateException("TURNSTILE_SECRET is required");
    }
    this.client = client;
    this.properties = properties;
  }

  @Override
  public boolean verify(String token, String remoteIp) {
    var form = new LinkedMultiValueMap<String, String>();
    form.add("secret", properties.getTurnstileSecret());
    form.add("response", token);
    if (remoteIp != null && !remoteIp.isBlank()) form.add("remoteip", remoteIp);
    try {
      VerificationResponse response = client.post()
          .uri(properties.getTurnstileVerifyUrl())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .body(VerificationResponse.class);
      return response != null
          && response.success()
          && properties.getTurnstileAction().equals(response.action())
          && properties.getTurnstileHostnames().contains(response.hostname());
    } catch (org.springframework.web.client.RestClientException e) {
      throw new TurnstileUnavailableException(e);
    }
  }

  private static RestClient configuredClient(
      RestClient.Builder builder,
      PublicSupportProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.getVerificationTimeout())
        .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.getVerificationTimeout());
    return builder.clone().requestFactory(requestFactory).build();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record VerificationResponse(boolean success, String hostname, String action) {}
}
