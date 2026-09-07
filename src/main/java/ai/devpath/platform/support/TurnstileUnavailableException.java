package ai.devpath.platform.support;

public class TurnstileUnavailableException extends RuntimeException {
  public TurnstileUnavailableException(Throwable cause) {
    super("Turnstile verification is unavailable", cause);
  }
}
