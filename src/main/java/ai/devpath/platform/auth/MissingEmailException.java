package ai.devpath.platform.auth;

/** OAuth 로그인 시 이메일을 확보하지 못함 → 로그인 거부(이메일 필수). */
public class MissingEmailException extends RuntimeException {
  public MissingEmailException(String provider) {
    super("이메일을 확보하지 못했습니다: " + provider);
  }
}
