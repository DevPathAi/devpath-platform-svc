package ai.devpath.platform.ads.dto;

import java.time.Instant;

public record AdRequest(
    String title, String imageUrl, String linkUrl, String slot,
    int weight, String status, Instant startsAt, Instant endsAt) {}
