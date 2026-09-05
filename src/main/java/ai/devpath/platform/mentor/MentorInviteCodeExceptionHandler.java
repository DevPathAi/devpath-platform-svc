package ai.devpath.platform.mentor;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
    MentorAccessController.class, AdminMentorInviteCodeController.class})
public class MentorInviteCodeExceptionHandler {
  private static final Map<String, HttpStatus> STATUSES = Map.of(
      "INVITE_CODE_INVALID", HttpStatus.UNPROCESSABLE_ENTITY,
      "INVITE_CODE_DISABLED", HttpStatus.UNPROCESSABLE_ENTITY,
      "INVITE_CODE_EXPIRED", HttpStatus.UNPROCESSABLE_ENTITY,
      "INVITE_CODE_EXHAUSTED", HttpStatus.CONFLICT,
      "MENTOR_ACCESS_MISSING", HttpStatus.CONFLICT);

  @ExceptionHandler(MentorInviteCodeException.class)
  public ResponseEntity<ErrorView> handle(MentorInviteCodeException exception) {
    return ResponseEntity.status(STATUSES.getOrDefault(exception.getCode(), HttpStatus.BAD_REQUEST))
        .body(new ErrorView(exception.getCode()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorView> invalidRequest() {
    return ResponseEntity.badRequest().body(new ErrorView("INVALID_REQUEST"));
  }

  public record ErrorView(String code) {}
}
