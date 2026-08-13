package ai.devpath.platform.ads;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 슬롯별 광고 소스 설정. 행이 없으면 HOUSE로 간주한다(마이그레이션 시드 이전 상태 방어). */
@Service
public class AdSlotConfigService {

  private final AdSlotConfigRepository repo;

  public AdSlotConfigService(AdSlotConfigRepository repo) {
    this.repo = repo;
  }

  @Transactional(readOnly = true)
  public AdSlotConfig get(String slot) {
    String validSlot = AdSlot.parse(slot);
    return repo.findById(validSlot).orElseGet(() -> {
      AdSlotConfig fallback = new AdSlotConfig();
      fallback.setSlot(validSlot);
      fallback.setSource(AdSlotSource.HOUSE);
      return fallback;
    });
  }

  @Transactional(readOnly = true)
  public List<AdSlotConfig> list() {
    return repo.findAllByOrderBySlotAsc();
  }

  /**
   * 슬롯 설정을 갱신한다.
   *
   * <p>source=ADSENSE인데 단위 ID가 비어도 저장을 허용한다 — 그 조합은 서빙에서 204로
   * 접히는 것이 정의된 동작이며, 여기서 거부하면 그 분기가 도달 불가능해진다.
   */
  @Transactional
  public AdSlotConfig update(String slot, String source, String adsenseSlotId) {
    String validSlot = AdSlot.parse(slot);
    String validSource = AdSlotSource.parse(source);

    AdSlotConfig row = repo.findById(validSlot).orElseGet(() -> {
      AdSlotConfig created = new AdSlotConfig();
      created.setSlot(validSlot);
      return created;
    });
    row.setSource(validSource);
    row.setAdsenseSlotId(normalize(adsenseSlotId));
    return repo.save(row);
  }

  /** 공백만 있는 입력은 null로 정규화한다(미설정과 같게 다루기 위해). */
  private static String normalize(String v) {
    if (v == null) {
      return null;
    }
    String trimmed = v.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
