package ai.devpath.platform.ads;

import ai.devpath.platform.ads.dto.AdRequest;
import ai.devpath.platform.ads.dto.AdRow;
import ai.devpath.platform.ads.dto.AdSettingsView;
import ai.devpath.platform.ads.dto.AdStatsRow;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.format.annotation.DateTimeFormat;
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

  @PostMapping("/bulk-delete")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void bulkDelete(@RequestBody Map<String, List<Long>> body) {
    service.bulkDelete(body.getOrDefault("ids", List.of()));
  }

  @PostMapping("/{id}/image")
  public AdRow uploadImage(@PathVariable long id,
      @RequestParam("file") MultipartFile file) throws IOException {
    String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("image");
    return imageService.upload(id, file.getBytes(), file.getContentType(), filename);
  }

  @GetMapping("/settings")
  public AdSettingsView settings() {
    return new AdSettingsView(settingsService.isEnabled());
  }

  @PutMapping("/settings")
  public AdSettingsView updateSettings(@RequestBody AdSettingsView body) {
    settingsService.setEnabled(body.enabled());
    return new AdSettingsView(settingsService.isEnabled());
  }

  @GetMapping("/{id}/stats")
  public List<AdStatsRow> stats(
      @PathVariable long id,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return statsService.stats(id, from, to);
  }
}
