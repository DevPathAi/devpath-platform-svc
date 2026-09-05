package ai.devpath.platform.support;

import ai.devpath.platform.support.dto.PublicSupportCreateRequest;
import ai.devpath.platform.support.dto.SupportCreatedView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/support")
public class PublicSupportController {
  private final PublicSupportService service;

  public PublicSupportController(PublicSupportService service) {
    this.service = service;
  }

  @PostMapping("/public-requests")
  public ResponseEntity<SupportCreatedView> create(
      @RequestBody PublicSupportCreateRequest request,
      HttpServletRequest servletRequest) {
    SupportRequest saved = service.create(request, servletRequest.getRemoteAddr());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new SupportCreatedView(saved.getId() == null ? 0L : saved.getId()));
  }
}
