package ai.devpath.platform.release;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
	ReleaseControlController.class,
	ReleaseBrowserController.class,
	ReleaseOAuthProviderController.class
})
public class ReleaseControlAdvice {
	@ExceptionHandler(ReleaseControlException.class)
	public ResponseEntity<Map<String, String>> rejected(ReleaseControlException exception) {
		HttpStatus status = switch (exception.kind()) {
			case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
			case NOT_FOUND -> HttpStatus.NOT_FOUND;
			case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
		};
		return ResponseEntity.status(status).body(Map.of("error", "release_request_rejected"));
	}
}
