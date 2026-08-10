package ai.devpath.platform.ads;

/**
 * 슬롯이 서빙할 내용. sealed로 두어 "ADSENSE인데 title이 있는" 표현 불가능한
 * 상태를 아예 만들 수 없게 한다.
 */
public sealed interface AdSlotContent {

  /** 하우스/스폰서 광고 1건. */
  record House(AdView ad) implements AdSlotContent {}

  /** 애드센스 광고 단위. 자체 노출·클릭 추적을 붙이지 않는다(구글 정책). */
  record Adsense(String adsenseSlotId) implements AdSlotContent {}
}
