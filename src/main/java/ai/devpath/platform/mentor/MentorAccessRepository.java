package ai.devpath.platform.mentor;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MentorAccessRepository extends JpaRepository<MentorAccess, Long> {
  Optional<MentorAccess> findByUserId(Long userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select m from MentorAccess m where m.userId = :userId")
  Optional<MentorAccess> findLockedByUserId(@Param("userId") Long userId);

  @Query(value = """
      SELECT ma.* FROM mentor_access ma
      JOIN users u ON u.id = ma.user_id
      WHERE ma.status = 'WAITLISTED'
        AND u.status = 'ACTIVE'
        AND u.deleted_at IS NULL
      ORDER BY ma.waitlisted_at, ma.id
      LIMIT :limit
      FOR UPDATE OF ma SKIP LOCKED
      """, nativeQuery = true)
  List<MentorAccess> lockNextWaitlisted(@Param("limit") int limit);
}
