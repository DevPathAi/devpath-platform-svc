package ai.devpath.platform.ads;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdvertisementRepository extends JpaRepository<Advertisement, Long> {

  @Query("select a from Advertisement a "
      + "where a.slot = :slot and a.status = 'ACTIVE' "
      + "and (a.startsAt is null or a.startsAt <= :now) "
      + "and (a.endsAt is null or a.endsAt > :now)")
  List<Advertisement> findEligible(@Param("slot") String slot, @Param("now") Instant now);
}
