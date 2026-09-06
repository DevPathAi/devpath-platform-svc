package ai.devpath.platform.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

  java.util.Optional<User> findByEmail(String email);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.id = :id")
  java.util.Optional<User> findLockedById(@Param("id") Long id);

  // keyset pagination for admin user list (id > cursor, ordered ASC)
  java.util.List<User> findByStatusAndIdGreaterThanOrderByIdAsc(
          String status, Long id, org.springframework.data.domain.Pageable pageable);

  java.util.List<User> findByIdGreaterThanOrderByIdAsc(
          Long id, org.springframework.data.domain.Pageable pageable);

  @Modifying
  @Query("update User u set u.onboardingStatus = 'IN_PROGRESS' "
       + "where u.id = :userId and u.onboardingStatus = 'PENDING'")
  int markAssessmentStartedIfPending(@Param("userId") Long userId);

  @Modifying
  @Query("update User u set u.onboardingStatus = 'DONE' "
       + "where u.id = :userId and u.onboardingStatus in ('PENDING', 'IN_PROGRESS')")
  int markOnboardingDoneIfPathGenerated(@Param("userId") Long userId);
}
