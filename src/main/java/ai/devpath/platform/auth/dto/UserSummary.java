package ai.devpath.platform.auth.dto;

public record UserSummary(String id, String email, String nickname, String role, String onboardingStatus,
		String consentStatus) {
	public static UserSummary of(ai.devpath.platform.user.User u) {
		return new UserSummary(String.valueOf(u.getId()), u.getEmail(), u.getNickname(), u.getRole(),
				u.getOnboardingStatus(), u.getConsentStatus());
	}
}
