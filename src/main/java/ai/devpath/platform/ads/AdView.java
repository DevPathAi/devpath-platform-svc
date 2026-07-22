package ai.devpath.platform.ads;

public record AdView(Long id, String title, String imageUrl, String linkUrl, String slot) {
  public static AdView of(Advertisement a) {
    return new AdView(a.getId(), a.getTitle(), a.getImageUrl(), a.getLinkUrl(), a.getSlot());
  }
}
