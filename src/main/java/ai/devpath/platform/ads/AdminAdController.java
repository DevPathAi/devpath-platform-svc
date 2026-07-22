package ai.devpath.platform.ads;

import ai.devpath.platform.ads.dto.AdRequest;
import ai.devpath.platform.ads.dto.AdRow;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** /admin/ads/** — SecurityConfig의 /admin/** hasRole("ADMIN")로 보호됨. */
@RestController
@RequestMapping("/admin/ads")
public class AdminAdController {

  private final AdAdminService service;

  public AdminAdController(AdAdminService service) {
    this.service = service;
  }

  @GetMapping
  public List<AdRow> list(@RequestParam(required = false) String slot,
      @RequestParam(required = false) String status) {
    return service.list(slot, status);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AdRow create(@RequestBody AdRequest req) {
    return service.create(req);
  }

  @PutMapping("/{id}")
  public AdRow update(@PathVariable long id, @RequestBody AdRequest req) {
    return service.update(id, req);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable long id) {
    service.delete(id);
  }
}
