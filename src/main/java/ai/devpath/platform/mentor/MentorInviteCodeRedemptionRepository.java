package ai.devpath.platform.mentor;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MentorInviteCodeRedemptionRepository
    extends JpaRepository<MentorInviteCodeRedemption, Long> {
  boolean existsByUserId(Long userId);
  long countByInviteCodeId(Long inviteCodeId);
}
