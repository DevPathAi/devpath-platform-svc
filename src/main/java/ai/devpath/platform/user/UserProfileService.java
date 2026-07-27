package ai.devpath.platform.user;

import ai.devpath.platform.user.dto.ProfileUpdateRequest;
import ai.devpath.platform.user.dto.ProfileView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {

  private static final int MAX_BIO = 500;

  private final UserProfileRepository profiles;

  public UserProfileService(UserProfileRepository profiles) {
    this.profiles = profiles;
  }

  @Transactional(readOnly = true)
  public ProfileView get(long userId) {
    return profiles
        .findById(userId)
        .map(ProfileView::of)
        .orElseGet(() -> new ProfileView(null, null, null, null, null));
  }

  @Transactional
  public ProfileView update(long userId, ProfileUpdateRequest req) {
    validate(req);
    UserProfile p =
        profiles
            .findById(userId)
            .orElseGet(
                () -> {
                  UserProfile np = new UserProfile();
                  np.setUserId(userId);
                  return np;
                });
    p.setBio(req.bio());
    p.setLearningGoal(req.learningGoal());
    p.setTargetTrack(req.targetTrack());
    p.setExperienceYears(req.experienceYears());
    return ProfileView.of(profiles.save(p));
  }

  private void validate(ProfileUpdateRequest req) {
    if (req.bio() != null && req.bio().length() > MAX_BIO) {
      throw new IllegalArgumentException("자기소개는 " + MAX_BIO + "자 이하여야 합니다");
    }
    if (req.experienceYears() != null && (req.experienceYears() < 0 || req.experienceYears() > 50)) {
      throw new IllegalArgumentException("경력 연차가 유효 범위(0~50)를 벗어났습니다");
    }
  }
}
