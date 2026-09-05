package ai.devpath.platform.support;

public class PublicSupportException extends RuntimeException {
  private final String code;

  public PublicSupportException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() { return code; }
}
