package ai.devpath.platform.mentor;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MentorAccessRepository extends JpaRepository<MentorAccess, Long> {
  Optional<MentorAccess> findByUserId(Long userId);

  @Query(value = """
      SELECT * FROM mentor_access
      WHERE status = 'WAITLISTED'
      ORDER BY waitlisted_at, id
      LIMIT :limit
      FOR UPDATE SKIP LOCKED
      """, nativeQuery = true)
  List<MentorAccess> lockNextWaitlisted(@Param("limit") int limit);
}
