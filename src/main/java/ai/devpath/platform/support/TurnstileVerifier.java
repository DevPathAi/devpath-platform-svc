package ai.devpath.platform.support;

public interface TurnstileVerifier {
  boolean verify(String token, String remoteIp);
}
