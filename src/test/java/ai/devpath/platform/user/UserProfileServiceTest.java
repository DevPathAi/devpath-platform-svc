package ai.devpath.platform.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.platform.user.dto.ProfileUpdateRequest;
import ai.devpath.platform.user.dto.ProfileView;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserProfileServiceTest {

  private UserProfileRepository profiles;
  private UserProfileService service;

  @BeforeEach
  void setup() {
    profiles = mock(UserProfileRepository.class);
    service = new UserProfileService(profiles);
    when(profiles.save(any(UserProfile.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void getReturnsEmptyViewWhenProfileMissing() {
    when(profiles.findById(1L)).thenReturn(Optional.empty());

    ProfileView view = service.get(1L);

    assertThat(view.avatar()).isNull();
    assertThat(view.bio()).isNull();
    assertThat(view.learningGoal()).isNull();
    assertThat(view.targetTrack()).isNull();
    assertThat(view.experienceYears()).isNull();
  }

  @Test
  void getReturnsExistingProfile() {
    UserProfile p = new UserProfile();
    p.setUserId(1L);
    p.setBio("hi");
    p.setLearningGoal("goal");
    p.setTargetTrack("backend");
    p.setExperienceYears(3);
    when(profiles.findById(1L)).thenReturn(Optional.of(p));

    ProfileView view = service.get(1L);

    assertThat(view.bio()).isEqualTo("hi");
    assertThat(view.learningGoal()).isEqualTo("goal");
    assertThat(view.targetTrack()).isEqualTo("backend");
    assertThat(view.experienceYears()).isEqualTo(3);
  }

  @Test
  void updateCreatesNewProfileWhenMissingAndSaves() {
    when(profiles.findById(1L)).thenReturn(Optional.empty());
    ProfileUpdateRequest req = new ProfileUpdateRequest("bio", "goal", "backend", 5);

    ProfileView view = service.update(1L, req);

    assertThat(view.bio()).isEqualTo("bio");
    assertThat(view.learningGoal()).isEqualTo("goal");
    assertThat(view.targetTrack()).isEqualTo("backend");
    assertThat(view.experienceYears()).isEqualTo(5);
    verify(profiles).save(any(UserProfile.class));
  }

  @Test
  void updateModifiesExistingProfile() {
    UserProfile existing = new UserProfile();
    existing.setUserId(1L);
    existing.setBio("old");
    when(profiles.findById(1L)).thenReturn(Optional.of(existing));
    ProfileUpdateRequest req = new ProfileUpdateRequest("new", null, null, null);

    ProfileView view = service.update(1L, req);

    assertThat(view.bio()).isEqualTo("new");
    assertThat(existing.getBio()).isEqualTo("new");
    verify(profiles).save(existing);
  }

  @Test
  void updateAllowsBioAtMaxLength() {
    when(profiles.findById(1L)).thenReturn(Optional.empty());
    String bio500 = "a".repeat(500);
    ProfileUpdateRequest req = new ProfileUpdateRequest(bio500, null, null, null);

    ProfileView view = service.update(1L, req);

    assertThat(view.bio()).hasSize(500);
    verify(profiles).save(any(UserProfile.class));
  }

  @Test
  void updateRejectsBioOverMaxLength() {
    String bio501 = "a".repeat(501);
    ProfileUpdateRequest req = new ProfileUpdateRequest(bio501, null, null, null);

    assertThatThrownBy(() -> service.update(1L, req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("500");
    verify(profiles, never()).save(any(UserProfile.class));
  }

  @Test
  void updateAllowsExperienceYearsAtBoundaries() {
    when(profiles.findById(1L)).thenReturn(Optional.empty());

    assertThat(service.update(1L, new ProfileUpdateRequest(null, null, null, 0)).experienceYears())
        .isEqualTo(0);
    assertThat(service.update(1L, new ProfileUpdateRequest(null, null, null, 50)).experienceYears())
        .isEqualTo(50);
  }

  @Test
  void updateRejectsNegativeExperienceYears() {
    ProfileUpdateRequest req = new ProfileUpdateRequest(null, null, null, -1);

    assertThatThrownBy(() -> service.update(1L, req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("0~50");
    verify(profiles, never()).save(any(UserProfile.class));
  }

  @Test
  void updateRejectsExperienceYearsAboveMax() {
    ProfileUpdateRequest req = new ProfileUpdateRequest(null, null, null, 51);

    assertThatThrownBy(() -> service.update(1L, req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("0~50");
    verify(profiles, never()).save(any(UserProfile.class));
  }

  @Test
  void updateAllowsNullBioAndNullExperience() {
    when(profiles.findById(1L)).thenReturn(Optional.empty());
    ProfileUpdateRequest req = new ProfileUpdateRequest(null, "goal", "track", null);

    ProfileView view = service.update(1L, req);

    assertThat(view.bio()).isNull();
    assertThat(view.experienceYears()).isNull();
    verify(profiles).save(any(UserProfile.class));
  }
}
