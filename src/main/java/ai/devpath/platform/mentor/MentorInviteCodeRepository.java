package ai.devpath.platform.mentor;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MentorInviteCodeRepository extends JpaRepository<MentorInviteCode, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from MentorInviteCode c where c.codeHash = :hash")
  Optional<MentorInviteCode> findLockedByCodeHash(@Param("hash") String hash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from MentorInviteCode c where c.id = :id")
  Optional<MentorInviteCode> findLockedById(@Param("id") Long id);
}
