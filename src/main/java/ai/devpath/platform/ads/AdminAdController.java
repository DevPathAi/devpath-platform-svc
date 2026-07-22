package ai.devpath.platform.ads;

import ai.devpath.platform.ads.dto.AdRequest;
import ai.devpath.platform.ads.dto.AdRow;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** /admin/ads/** — SecurityConfig의 /admin/** hasRole("ADMIN")로 보호됨. */
@RestController
@RequestMapping("/admin/ads")
public class AdminAdController {

  private final AdAdminService service;
  private final AdImageService imageService;
  private final AdSettingsService settingsService;
  private final AdStatsService statsService;

  public AdminAdController(AdAdminService service, AdImageService imageService,
      AdSettingsService settingsService, AdStatsService statsService) {
    this.service = service;
    this.imageService = imageService;
    this.settingsService = settingsService;
    this.statsService = statsService;
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

  @PostMapping("/{id}/image")
  public AdRow uploadImage(@PathVariable long id,
      @RequestParam("file") MultipartFile file) throws IOException {
    return imageService.upload(id, file.getBytes(), file.getContentType(), file.getOriginalFilename());
  }

  @GetMapping("/settings")
  public ai.devpath.platform.ads.dto.AdSettingsView settings() {
    return new ai.devpath.platform.ads.dto.AdSettingsView(settingsService.isEnabled());
  }

  @PutMapping("/settings")
  public ai.devpath.platform.ads.dto.AdSettingsView updateSettings(
      @RequestBody ai.devpath.platform.ads.dto.AdSettingsView body) {
    settingsService.setEnabled(body.enabled());
    return new ai.devpath.platform.ads.dto.AdSettingsView(settingsService.isEnabled());
  }

  @GetMapping("/{id}/stats")
  public java.util.List<ai.devpath.platform.ads.dto.AdStatsRow> stats(
      @PathVariable long id,
      @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
      @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
    return statsService.stats(id, from, to);
  }
}
