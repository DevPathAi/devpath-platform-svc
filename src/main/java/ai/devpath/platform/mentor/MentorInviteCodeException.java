package ai.devpath.platform.mentor;

public class MentorInviteCodeException extends RuntimeException {
  private final String code;

  public MentorInviteCodeException(String code) {
    super(code);
    this.code = code;
  }

  public String getCode() { return code; }
}
