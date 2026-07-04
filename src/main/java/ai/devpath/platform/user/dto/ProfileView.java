package ai.devpath.platform.user.dto;

import ai.devpath.platform.user.UserProfile;

public record ProfileView(
    String avatar, String bio, String learningGoal, String targetTrack, Integer experienceYears) {
  public static ProfileView of(UserProfile p) {
    return new ProfileView(
        p.getAvatar(), p.getBio(), p.getLearningGoal(), p.getTargetTrack(), p.getExperienceYears());
  }
}
