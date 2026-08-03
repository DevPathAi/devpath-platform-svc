package ai.devpath.platform.support;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * keyset 페이지네이션 — <b>id 내림차순(최신순)</b>.
 * AdminUserController(사용자 목록)는 가입 순이 자연스러워 오름차순이지만, 제보 목록은
 * 최신순이어야 하므로 방향만 반대다. 응답 계약({data,nextCursor,limit})은 동일하다.
 */
public interface SupportRequestRepository extends JpaRepository<SupportRequest, Long> {

  List<SupportRequest> findByIdLessThanOrderByIdDesc(long cursor, Pageable pageable);

  List<SupportRequest> findByStatusAndIdLessThanOrderByIdDesc(
      String status, long cursor, Pageable pageable);

  List<SupportRequest> findByTypeAndIdLessThanOrderByIdDesc(
      String type, long cursor, Pageable pageable);

  List<SupportRequest> findByStatusAndTypeAndIdLessThanOrderByIdDesc(
      String status, String type, long cursor, Pageable pageable);
}
