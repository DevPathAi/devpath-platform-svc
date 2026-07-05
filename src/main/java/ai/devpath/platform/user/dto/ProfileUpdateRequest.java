package ai.devpath.platform.user.dto;

public record ProfileUpdateRequest(
    String bio, String learningGoal, String targetTrack, Integer experienceYears) {}
