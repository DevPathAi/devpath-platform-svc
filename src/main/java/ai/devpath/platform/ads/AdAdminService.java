package ai.devpath.platform.ads;

import ai.devpath.platform.ads.dto.AdRequest;
import ai.devpath.platform.ads.dto.AdRow;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdAdminService {

  private static final Set<String> STATUSES = Set.of("ACTIVE", "PAUSED");
  private final AdvertisementRepository repo;

  public AdAdminService(AdvertisementRepository repo) {
    this.repo = repo;
  }

  @Transactional
  public AdRow create(AdRequest req) {
    Advertisement a = new Advertisement();
    apply(a, req);
    return AdRow.of(repo.save(a));
  }

  @Transactional
  public AdRow update(long id, AdRequest req) {
    Advertisement a = repo.findById(id).orElseThrow(() -> new AdNotFoundException(id));
    apply(a, req);
    return AdRow.of(repo.save(a));
  }

  @Transactional
  public void delete(long id) {
    if (!repo.existsById(id)) {
      throw new AdNotFoundException(id);
    }
    repo.deleteById(id); // ad_daily_stats는 FK ON DELETE CASCADE
  }

  @Transactional(readOnly = true)
  public List<AdRow> list(String slot, String status) {
    return repo.findAll(Sort.by(Sort.Direction.DESC, "id")).stream()
        .filter(a -> slot == null || a.getSlot().equals(slot))
        .filter(a -> status == null || a.getStatus().equals(status))
        .map(AdRow::of)
        .toList();
  }

  private void apply(Advertisement a, AdRequest req) {
    if (req.title() == null || req.title().isBlank()) {
      throw new IllegalArgumentException("title은 필수입니다");
    }
    if (req.linkUrl() == null || req.linkUrl().isBlank()) {
      throw new IllegalArgumentException("linkUrl은 필수입니다");
    }
    if (req.weight() < 1) {
      throw new IllegalArgumentException("weight는 1 이상이어야 합니다");
    }
    if (req.status() == null || !STATUSES.contains(req.status())) {
      throw new IllegalArgumentException("유효하지 않은 status: " + req.status());
    }
    a.setTitle(req.title());
    a.setImageUrl(req.imageUrl());
    a.setLinkUrl(req.linkUrl());
    a.setSlot(AdSlot.parse(req.slot()));
    a.setWeight(req.weight());
    a.setStatus(req.status());
    a.setStartsAt(req.startsAt());
    a.setEndsAt(req.endsAt());
  }
}
