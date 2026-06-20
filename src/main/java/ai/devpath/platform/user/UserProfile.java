package ai.devpath.platform.user;

import jakarta.persistence.*;

@Entity
@Table(name = "user_profiles")
public class UserProfile {
	@Id @Column(name = "user_id") private Long userId;
	private String avatar;
	private String bio;
	@Column(name = "learning_goal") private String learningGoal;
	@Column(name = "target_track") private String targetTrack;
	@Column(name = "experience_years") private Integer experienceYears;

	public Long getUserId() { return userId; }
	public void setUserId(Long v) { this.userId = v; }
	public String getAvatar() { return avatar; }
	public void setAvatar(String v) { this.avatar = v; }
	public String getBio() { return bio; }
	public void setBio(String v) { this.bio = v; }
	public String getLearningGoal() { return learningGoal; }
	public void setLearningGoal(String v) { this.learningGoal = v; }
	public String getTargetTrack() { return targetTrack; }
	public void setTargetTrack(String v) { this.targetTrack = v; }
	public Integer getExperienceYears() { return experienceYears; }
	public void setExperienceYears(Integer v) { this.experienceYears = v; }
}
