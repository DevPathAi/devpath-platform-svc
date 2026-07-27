package ai.devpath.platform.ads;

public enum AdEventType {
  IMPRESSION,
  CLICK;

  public static AdEventType parse(String v) {
    try {
      return AdEventType.valueOf(v);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IllegalArgumentException("유효하지 않은 이벤트 타입: " + v);
    }
  }
}
