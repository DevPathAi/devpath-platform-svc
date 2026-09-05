package ai.devpath.platform.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("devpath.public-support")
public class PublicSupportProperties {
  private String turnstileSecret;
  private String turnstileVerifyUrl = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
  private String turnstileAction = "public_support";
  private List<String> turnstileHostnames = List.of("leva.ai.kr", "localhost", "127.0.0.1");
  private Duration verificationTimeout = Duration.ofSeconds(5);
  private String rateLimitHmacSecret;
  private Duration rateLimitWindow = Duration.ofHours(1);
  private int rateLimitPerIp = 10;
  private int rateLimitPerEmail = 5;

  public String getTurnstileSecret() { return turnstileSecret; }
  public void setTurnstileSecret(String value) { this.turnstileSecret = value; }
  public String getTurnstileVerifyUrl() { return turnstileVerifyUrl; }
  public void setTurnstileVerifyUrl(String value) { this.turnstileVerifyUrl = value; }
  public String getTurnstileAction() { return turnstileAction; }
  public void setTurnstileAction(String value) { this.turnstileAction = value; }
  public List<String> getTurnstileHostnames() { return turnstileHostnames; }
  public void setTurnstileHostnames(List<String> value) { this.turnstileHostnames = value; }
  public Duration getVerificationTimeout() { return verificationTimeout; }
  public void setVerificationTimeout(Duration value) { this.verificationTimeout = value; }
  public String getRateLimitHmacSecret() { return rateLimitHmacSecret; }
  public void setRateLimitHmacSecret(String value) { this.rateLimitHmacSecret = value; }
  public Duration getRateLimitWindow() { return rateLimitWindow; }
  public void setRateLimitWindow(Duration value) { this.rateLimitWindow = value; }
  public int getRateLimitPerIp() { return rateLimitPerIp; }
  public void setRateLimitPerIp(int value) { this.rateLimitPerIp = value; }
  public int getRateLimitPerEmail() { return rateLimitPerEmail; }
  public void setRateLimitPerEmail(int value) { this.rateLimitPerEmail = value; }
}
