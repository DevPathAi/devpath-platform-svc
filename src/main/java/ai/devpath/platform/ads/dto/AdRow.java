package ai.devpath.platform.ads.dto;

import ai.devpath.platform.ads.Advertisement;
import java.time.Instant;

public record AdRow(
    Long id, String title, String imageUrl, String linkUrl, String slot,
    int weight, String status, Instant startsAt, Instant endsAt) {
  public static AdRow of(Advertisement a) {
    return new AdRow(a.getId(), a.getTitle(), a.getImageUrl(), a.getLinkUrl(), a.getSlot(),
        a.getWeight(), a.getStatus(), a.getStartsAt(), a.getEndsAt());
  }
}
