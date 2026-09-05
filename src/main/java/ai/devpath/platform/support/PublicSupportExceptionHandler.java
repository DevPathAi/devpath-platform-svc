package ai.devpath.platform.support;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PublicSupportController.class)
public class PublicSupportExceptionHandler {
  private static final Map<String, HttpStatus> STATUSES = Map.of(
      "TURNSTILE_FAILED", HttpStatus.UNPROCESSABLE_ENTITY,
      "TURNSTILE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
      "RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS);

  @ExceptionHandler(PublicSupportException.class)
  public ResponseEntity<PublicSupportErrorView> handle(PublicSupportException exception) {
    HttpStatus status = STATUSES.getOrDefault(exception.getCode(), HttpStatus.BAD_REQUEST);
    return ResponseEntity.status(status).body(new PublicSupportErrorView(exception.getCode()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<PublicSupportErrorView> handleInvalidRequest() {
    return ResponseEntity.badRequest().body(new PublicSupportErrorView("INVALID_REQUEST"));
  }

  public record PublicSupportErrorView(String code) {}
}
