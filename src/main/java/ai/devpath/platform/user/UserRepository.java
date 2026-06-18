package ai.devpath.platform.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
  @Modifying
  @Query("update User u set u.onboardingStatus = 'IN_PROGRESS' "
       + "where u.id = :userId and u.onboardingStatus = 'PENDING'")
  int markAssessmentStartedIfPending(@Param("userId") Long userId);
}
