# 베타 광고 P1 백엔드 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** platform-svc에 자체 하우스/스폰서 광고의 서빙·측정·admin 관리 백엔드를 추가하고, shared에 스키마를 마이그레이션한다.

**Architecture:** 광고는 platform-svc의 신규 `ads` 패키지가 소유한다. 웹은 `GET /ads?slot=`으로 슬롯당 가중치 랜덤 광고 1개를 받고, `POST /ads/{id}/events`로 노출/클릭을 `ad_daily_stats`에 직접 UPSERT한다. admin은 `/admin/ads/**`로 CRUD·이미지 업로드·전역 토글·통계를 관리한다. 이미지는 기존 `S3ObjectStorage`를 재사용한다.

**Tech Stack:** Spring Boot 4.0.7 · Java 21 · Spring Data JPA · PostgreSQL · devpath-shared(Flyway·ObjectStorage·ApiException) · JUnit 5 · Mockito(`@MockitoBean`)

## Global Constraints

- 패키지 루트 `ai.devpath.platform.ads` (신규). 스키마는 **devpath-shared** 중앙 Flyway가 소유(platform은 `flyway.enabled=false`).
- 슬롯 enum 값(문자열, DB CHECK와 일치): `DASHBOARD_TOP`, `COMMUNITY_FEED`, `CONTENT_PAGE`.
- 광고 상태 값: `ACTIVE`, `PAUSED`.
- 이벤트 타입 값: `IMPRESSION`, `CLICK`.
- 에러: 잘못된 입력 → `IllegalArgumentException`(공용 `ApiExceptionHandler`가 `VALIDATION_FAILED` 400). 광고 없음 → `AdNotFoundException extends ApiException(ErrorCode.RESOURCE_NOT_FOUND)` 404. 스토리지 미가용 → `StorageException`(503, shared).
- 보안: `/admin/ads/**`는 기존 SecurityConfig `/admin/**` → `hasRole("ADMIN")`으로 이미 보호됨. `/ads/**`는 `.anyRequest().authenticated()`로 이미 보호됨. **SecurityConfig 변경 없음.**
- 인증 주체: 컨트롤러는 `@AuthenticationPrincipal Jwt jwt`, userId=`Long.parseLong(jwt.getSubject())`.
- 테스트: 실패 테스트 먼저(TDD). DB가 필요한 통합 테스트는 `@SpringBootTest @ActiveProfiles("test")`(기존 25개 테스트와 동일 설정 → 컨텍스트 캐시 공유). 커넥션 초과 flake 예방으로 `application-test.yml`에 `spring.datasource.hikari.maximum-pool-size: 4` 추가(Task 1).
- 검증 명령: platform `./gradlew test --tests '<FQN>' --offline`, shared `./gradlew flywayMigrate`/`test`. 로컬 인프라(docker compose)와 DB가 떠 있어야 통합 테스트 통과.

---

## File Structure

**devpath-shared** (마이그레이션):
- Create `src/main/resources/db/migration/V202607221001__advertisement.sql`
- Create `src/main/resources/db/migration/V202607221002__ad_settings.sql`
- Create `src/main/resources/db/migration/V202607221003__ad_daily_stats.sql`

**devpath-platform-svc** (`ai.devpath.platform.ads`):
- Create `Advertisement.java` (엔티티), `AdvertisementRepository.java`
- Create `AdSettings.java` (엔티티), `AdSettingsRepository.java`, `AdSettingsService.java`
- Create `AdDailyStats.java` (엔티티), `AdDailyStatsRepository.java`
- Create `AdSlot.java`(enum), `AdView.java`(웹 응답 DTO), `AdServeService.java`, `AdController.java`
- Create `AdEventType.java`(enum), `AdEventService.java`, `AdNotFoundException.java`
- Create `AdAdminService.java`, `AdminAdController.java`, `dto/AdRequest.java`, `dto/AdRow.java`, `dto/AdStatsRow.java`, `dto/AdSettingsView.java`, `AdImageService.java`
- Modify `src/test/resources/application-test.yml` (hikari 캡)

**devpath-gateway**:
- Modify `src/main/resources/application.yml` (라우트 `/ads/**` → platform)

---

## Task 1: shared 스키마 마이그레이션 + 테스트 hikari 캡

**Files:**
- Create: `devpath-shared/src/main/resources/db/migration/V202607221001__advertisement.sql`
- Create: `devpath-shared/src/main/resources/db/migration/V202607221002__ad_settings.sql`
- Create: `devpath-shared/src/main/resources/db/migration/V202607221003__ad_daily_stats.sql`
- Modify: `devpath-platform-svc/src/test/resources/application-test.yml`

**Interfaces:**
- Produces: 테이블 `advertisement`, `ad_settings`(단일행 시드), `ad_daily_stats`. 컬럼·제약은 아래 SQL 그대로.

- [ ] **Step 1: advertisement 마이그레이션 작성**

`V202607221001__advertisement.sql`:
```sql
CREATE TABLE advertisement (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title       VARCHAR(200)  NOT NULL,
    image_url   VARCHAR(1000),
    link_url    VARCHAR(1000) NOT NULL,
    slot        VARCHAR(30)   NOT NULL,
    weight      INT           NOT NULL DEFAULT 1,
    status      VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    starts_at   TIMESTAMPTZ,
    ends_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_ad_slot   CHECK (slot   IN ('DASHBOARD_TOP','COMMUNITY_FEED','CONTENT_PAGE')),
    CONSTRAINT chk_ad_status CHECK (status IN ('ACTIVE','PAUSED')),
    CONSTRAINT chk_ad_weight CHECK (weight >= 1)
);
CREATE INDEX idx_ad_slot_status ON advertisement (slot, status);
CREATE TRIGGER advertisement_set_updated_at BEFORE UPDATE ON advertisement
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
```
> 주의: `set_updated_at()` 함수는 기존 마이그레이션에 정의돼 있어야 한다(users 트리거가 사용). Step 2에서 존재를 확인한다. 없으면 이 파일 상단에서 `CREATE FUNCTION`으로 정의.

- [ ] **Step 2: set_updated_at 함수 존재 확인**

Run: `grep -rn "set_updated_at" devpath-shared/src/main/resources/db/migration/`
Expected: `FUNCTION set_updated_at()` 정의 라인 존재(예: `V202606150900__init_common.sql`). 존재하면 위 트리거 그대로 사용. 없으면 advertisement 마이그레이션 상단에 함수 정의를 먼저 추가.

- [ ] **Step 3: ad_settings 마이그레이션 작성 (단일행 시드)**

`V202607221002__ad_settings.sql`:
```sql
CREATE TABLE ad_settings (
    id         INT         NOT NULL PRIMARY KEY,
    enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ad_settings_singleton CHECK (id = 1)
);
INSERT INTO ad_settings (id, enabled) VALUES (1, TRUE);
CREATE TRIGGER ad_settings_set_updated_at BEFORE UPDATE ON ad_settings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
```

- [ ] **Step 4: ad_daily_stats 마이그레이션 작성**

`V202607221003__ad_daily_stats.sql`:
```sql
CREATE TABLE ad_daily_stats (
    ad_id       BIGINT NOT NULL REFERENCES advertisement(id) ON DELETE CASCADE,
    stat_date   DATE   NOT NULL,
    impressions BIGINT NOT NULL DEFAULT 0,
    clicks      BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (ad_id, stat_date)
);
```

- [ ] **Step 5: 마이그레이션 적용 검증**

Run: `cd devpath-shared && ./gradlew flywayMigrate --info 2>&1 | tail -5`
Expected: `Successfully applied 3 migrations` 또는 최신까지 적용. 이후:
Run: `docker exec devpath-local-postgres-1 psql -U devpath -d devpath -c "SELECT enabled FROM ad_settings; \d advertisement"`
Expected: `enabled = t` 1행, advertisement 테이블에 chk 제약 표시.

- [ ] **Step 6: platform 테스트 hikari 캡 추가**

`devpath-platform-svc/src/test/resources/application-test.yml`의 `spring:` 아래에 추가:
```yaml
  datasource:
    hikari:
      maximum-pool-size: 4
```

- [ ] **Step 7: 커밋 (각 레포)**

```bash
cd devpath-shared && git add src/main/resources/db/migration/V20260722*.sql && git commit -m "feat(ads): advertisement·ad_settings·ad_daily_stats 스키마"
cd ../devpath-platform-svc && git add src/test/resources/application-test.yml && git commit -m "test(ads): 테스트 hikari 풀 캡"
```
> shared는 별도 브랜치(`feat/beta-ads-schema`)에서 작업 후 develop PR. 머지 후 `gh workflow run publish.yml --ref develop`.

---

## Task 2: Advertisement 엔티티 + 리포지토리

**Files:**
- Create: `src/main/java/ai/devpath/platform/ads/Advertisement.java`
- Create: `src/main/java/ai/devpath/platform/ads/AdvertisementRepository.java`
- Test: `src/test/java/ai/devpath/platform/ads/AdvertisementRepositoryTest.java`

**Interfaces:**
- Produces: `Advertisement`(getters/setters: id,title,imageUrl,linkUrl,slot,weight,status,startsAt,endsAt), `AdvertisementRepository.findEligible(String slot, Instant now)` → `List<Advertisement>` (status=ACTIVE·스케줄 창 내).

- [ ] **Step 1: 실패 테스트 작성**

`AdvertisementRepositoryTest.java`:
```java
package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdvertisementRepositoryTest {

  @Autowired AdvertisementRepository repo;

  @Test
  void findEligibleReturnsActiveWithinSchedule() {
    Instant now = Instant.now();
    repo.save(ad("active-open", "DASHBOARD_TOP", "ACTIVE", null, null));
    repo.save(ad("paused", "DASHBOARD_TOP", "PAUSED", null, null));
    repo.save(ad("future", "DASHBOARD_TOP", "ACTIVE", now.plus(1, ChronoUnit.DAYS), null));
    repo.save(ad("ended", "DASHBOARD_TOP", "ACTIVE", null, now.minus(1, ChronoUnit.DAYS)));
    repo.save(ad("other-slot", "COMMUNITY_FEED", "ACTIVE", null, null));

    List<Advertisement> eligible = repo.findEligible("DASHBOARD_TOP", now);

    assertThat(eligible).extracting(Advertisement::getTitle).containsExactly("active-open");
  }

  private Advertisement ad(String title, String slot, String status, Instant starts, Instant ends) {
    Advertisement a = new Advertisement();
    a.setTitle(title);
    a.setLinkUrl("https://example.com");
    a.setSlot(slot);
    a.setStatus(status);
    a.setWeight(1);
    a.setStartsAt(starts);
    a.setEndsAt(ends);
    return a;
  }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests '*AdvertisementRepositoryTest*' --offline`
Expected: 컴파일 실패(`Advertisement` 없음).

- [ ] **Step 3: 엔티티 구현**

`Advertisement.java`:
```java
package ai.devpath.platform.ads;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "advertisement")
public class Advertisement {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String title;

  @Column(name = "image_url")
  private String imageUrl;

  @Column(name = "link_url")
  private String linkUrl;

  private String slot;
  private int weight = 1;
  private String status = "ACTIVE";

  @Column(name = "starts_at")
  private Instant startsAt;

  @Column(name = "ends_at")
  private Instant endsAt;

  public Long getId() { return id; }
  public String getTitle() { return title; }
  public void setTitle(String v) { this.title = v; }
  public String getImageUrl() { return imageUrl; }
  public void setImageUrl(String v) { this.imageUrl = v; }
  public String getLinkUrl() { return linkUrl; }
  public void setLinkUrl(String v) { this.linkUrl = v; }
  public String getSlot() { return slot; }
  public void setSlot(String v) { this.slot = v; }
  public int getWeight() { return weight; }
  public void setWeight(int v) { this.weight = v; }
  public String getStatus() { return status; }
  public void setStatus(String v) { this.status = v; }
  public Instant getStartsAt() { return startsAt; }
  public void setStartsAt(Instant v) { this.startsAt = v; }
  public Instant getEndsAt() { return endsAt; }
  public void setEndsAt(Instant v) { this.endsAt = v; }
}
```

- [ ] **Step 4: 리포지토리 구현**

`AdvertisementRepository.java`:
```java
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
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests '*AdvertisementRepositoryTest*' --offline`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/ai/devpath/platform/ads/Advertisement.java src/main/java/ai/devpath/platform/ads/AdvertisementRepository.java src/test/java/ai/devpath/platform/ads/AdvertisementRepositoryTest.java
git commit -m "feat(ads): Advertisement 엔티티+적격 조회 리포지토리"
```

---

## Task 3: AdSettings 엔티티 + 서비스 (전역 토글)

**Files:**
- Create: `src/main/java/ai/devpath/platform/ads/AdSettings.java`
- Create: `src/main/java/ai/devpath/platform/ads/AdSettingsRepository.java`
- Create: `src/main/java/ai/devpath/platform/ads/AdSettingsService.java`
- Test: `src/test/java/ai/devpath/platform/ads/AdSettingsServiceTest.java`

**Interfaces:**
- Produces: `AdSettingsService.isEnabled()` → `boolean`, `AdSettingsService.setEnabled(boolean)` → `void`.

- [ ] **Step 1: 실패 테스트 작성**

`AdSettingsServiceTest.java`:
```java
package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdSettingsServiceTest {

  @Autowired AdSettingsService service;

  @Test
  void defaultsToEnabledFromSeed() {
    assertThat(service.isEnabled()).isTrue();
  }

  @Test
  void setEnabledPersists() {
    service.setEnabled(false);
    assertThat(service.isEnabled()).isFalse();
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*AdSettingsServiceTest*' --offline`
Expected: 컴파일 실패.

- [ ] **Step 3: 엔티티 구현**

`AdSettings.java`:
```java
package ai.devpath.platform.ads;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ad_settings")
public class AdSettings {
  @Id
  private Integer id;
  private boolean enabled;

  public Integer getId() { return id; }
  public void setId(Integer v) { this.id = v; }
  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean v) { this.enabled = v; }
}
```

- [ ] **Step 4: 리포지토리 구현**

`AdSettingsRepository.java`:
```java
package ai.devpath.platform.ads;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AdSettingsRepository extends JpaRepository<AdSettings, Integer> {}
```

- [ ] **Step 5: 서비스 구현**

`AdSettingsService.java`:
```java
package ai.devpath.platform.ads;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 전역 광고 on/off. 단일행(id=1)을 읽고 쓴다. */
@Service
public class AdSettingsService {

  private static final int SINGLETON_ID = 1;
  private final AdSettingsRepository repo;

  public AdSettingsService(AdSettingsRepository repo) {
    this.repo = repo;
  }

  @Transactional(readOnly = true)
  public boolean isEnabled() {
    return repo.findById(SINGLETON_ID).map(AdSettings::isEnabled).orElse(false);
  }

  @Transactional
  public void setEnabled(boolean enabled) {
    AdSettings s = repo.findById(SINGLETON_ID).orElseGet(() -> {
      AdSettings created = new AdSettings();
      created.setId(SINGLETON_ID);
      return created;
    });
    s.setEnabled(enabled);
    repo.save(s);
  }
}
```

- [ ] **Step 6: 통과 확인 + 커밋**

Run: `./gradlew test --tests '*AdSettingsServiceTest*' --offline`
Expected: PASS.
```bash
git add src/main/java/ai/devpath/platform/ads/AdSettings.java src/main/java/ai/devpath/platform/ads/AdSettingsRepository.java src/main/java/ai/devpath/platform/ads/AdSettingsService.java src/test/java/ai/devpath/platform/ads/AdSettingsServiceTest.java
git commit -m "feat(ads): 전역 토글 AdSettings 서비스"
```

---

## Task 4: 서빙 — AdServeService + AdController (GET /ads)

**Files:**
- Create: `src/main/java/ai/devpath/platform/ads/AdSlot.java`
- Create: `src/main/java/ai/devpath/platform/ads/AdView.java`
- Create: `src/main/java/ai/devpath/platform/ads/AdServeService.java`
- Create: `src/main/java/ai/devpath/platform/ads/AdController.java`
- Test: `src/test/java/ai/devpath/platform/ads/AdServeServiceTest.java`

**Interfaces:**
- Consumes: `AdvertisementRepository.findEligible`, `AdSettingsService.isEnabled`.
- Produces: `AdServeService.serve(String slot, long userId)` → `Optional<AdView>`; `AdView`(record: Long id, String title, String imageUrl, String linkUrl, String slot); `AdSlot.parse(String)` → 유효 slot 문자열(부정 시 IllegalArgumentException).

- [ ] **Step 1: 실패 테스트 작성 (가중치 선택은 Random 주입으로 결정화)**

`AdServeServiceTest.java`:
```java
package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AdServeServiceTest {

  private final AdvertisementRepository repo = mock(AdvertisementRepository.class);
  private final AdSettingsService settings = mock(AdSettingsService.class);

  @Test
  void returnsEmptyWhenGloballyDisabled() {
    when(settings.isEnabled()).thenReturn(false);
    AdServeService svc = new AdServeService(repo, settings, new Random(0));
    assertThat(svc.serve("DASHBOARD_TOP", 1L)).isEmpty();
  }

  @Test
  void returnsEmptyWhenNoEligible() {
    when(settings.isEnabled()).thenReturn(true);
    when(repo.findEligible(anyString(), any(Instant.class))).thenReturn(List.of());
    AdServeService svc = new AdServeService(repo, settings, new Random(0));
    assertThat(svc.serve("DASHBOARD_TOP", 1L)).isEmpty();
  }

  @Test
  void weightedSelectionPicksByRandom() {
    when(settings.isEnabled()).thenReturn(true);
    Advertisement a = ad(10L, "A", 1);
    Advertisement b = ad(20L, "B", 3); // 총 weight 4, [0,1)->A, [1,4)->B
    when(repo.findEligible(anyString(), any(Instant.class))).thenReturn(List.of(a, b));

    Random fixed = mock(Random.class);
    when(fixed.nextInt(4)).thenReturn(2); // 2 → 누적 A(1) 초과 → B
    AdServeService svc = new AdServeService(repo, settings, fixed);

    Optional<AdView> view = svc.serve("DASHBOARD_TOP", 1L);
    assertThat(view).isPresent();
    assertThat(view.get().id()).isEqualTo(20L);
  }

  private Advertisement ad(long id, String title, int weight) {
    Advertisement a = new Advertisement();
    a.setTitle(title);
    a.setLinkUrl("https://e.com");
    a.setSlot("DASHBOARD_TOP");
    a.setWeight(weight);
    a.setStatus("ACTIVE");
    try {
      var f = Advertisement.class.getDeclaredField("id");
      f.setAccessible(true);
      f.set(a, id);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
    return a;
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*AdServeServiceTest*' --offline`
Expected: 컴파일 실패(`AdServeService`/`AdView` 없음).

- [ ] **Step 3: AdSlot enum 구현**

`AdSlot.java`:
```java
package ai.devpath.platform.ads;

import java.util.Set;

/** 유효 슬롯 문자열 카탈로그. DB CHECK(chk_ad_slot)과 일치. */
public final class AdSlot {
  public static final String DASHBOARD_TOP = "DASHBOARD_TOP";
  public static final String COMMUNITY_FEED = "COMMUNITY_FEED";
  public static final String CONTENT_PAGE = "CONTENT_PAGE";

  private static final Set<String> ALL = Set.of(DASHBOARD_TOP, COMMUNITY_FEED, CONTENT_PAGE);

  private AdSlot() {}

  /** 유효 슬롯이면 그대로 반환, 아니면 IllegalArgumentException(→400). */
  public static String parse(String slot) {
    if (slot == null || !ALL.contains(slot)) {
      throw new IllegalArgumentException("유효하지 않은 슬롯: " + slot);
    }
    return slot;
  }
}
```

- [ ] **Step 4: AdView DTO 구현**

`AdView.java`:
```java
package ai.devpath.platform.ads;

public record AdView(Long id, String title, String imageUrl, String linkUrl, String slot) {
  public static AdView of(Advertisement a) {
    return new AdView(a.getId(), a.getTitle(), a.getImageUrl(), a.getLinkUrl(), a.getSlot());
  }
}
```

- [ ] **Step 5: AdServeService 구현 (가중치 랜덤)**

`AdServeService.java`:
```java
package ai.devpath.platform.ads;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 슬롯별 적격 광고를 가중치 랜덤으로 1개 서빙한다. */
@Service
public class AdServeService {

  private final AdvertisementRepository ads;
  private final AdSettingsService settings;
  private final Random random;

  public AdServeService(AdvertisementRepository ads, AdSettingsService settings, Random random) {
    this.ads = ads;
    this.settings = settings;
    this.random = random;
  }

  @Transactional(readOnly = true)
  public Optional<AdView> serve(String slot, long userId) {
    if (!settings.isEnabled() || !userShouldSeeAds(userId)) {
      return Optional.empty();
    }
    List<Advertisement> eligible = ads.findEligible(AdSlot.parse(slot), Instant.now());
    if (eligible.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(AdView.of(pickWeighted(eligible)));
  }

  /**
   * 무료기간 게이팅 predicate. 지금은 전원 노출(전원 free). 후속 유료 티어 도입 시
   * user.tier == FREE 조건으로만 확장한다(결제 미의존).
   */
  private boolean userShouldSeeAds(long userId) {
    return true;
  }

  private Advertisement pickWeighted(List<Advertisement> eligible) {
    int total = eligible.stream().mapToInt(Advertisement::getWeight).sum();
    int r = random.nextInt(total);
    int cumulative = 0;
    for (Advertisement a : eligible) {
      cumulative += a.getWeight();
      if (r < cumulative) {
        return a;
      }
    }
    return eligible.get(eligible.size() - 1); // 방어(도달 불가)
  }
}
```
> `Random` 빈은 Task 4 Step 7에서 등록. 프로덕션은 새 `Random()`.

- [ ] **Step 6: AdController 구현 (204 처리)**

`AdController.java`:
```java
package ai.devpath.platform.ads;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdController {

  private final AdServeService serve;

  public AdController(AdServeService serve) {
    this.serve = serve;
  }

  /** GET /ads?slot=DASHBOARD_TOP — 적격 광고 1개(200) 또는 없음(204). */
  @GetMapping("/ads")
  public ResponseEntity<AdView> ad(@AuthenticationPrincipal Jwt jwt, @RequestParam String slot) {
    return serve.serve(slot, Long.parseLong(jwt.getSubject()))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }
}
```

- [ ] **Step 7: Random 빈 등록**

`src/main/java/ai/devpath/platform/ads/AdsConfig.java`:
```java
package ai.devpath.platform.ads;

import java.util.Random;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdsConfig {
  @Bean
  public Random adRandom() {
    return new Random();
  }
}
```
> 주의: 앱에 다른 `Random` 빈이 없어야 한다. 있으면 `@Qualifier("adRandom")`로 구분(현재 없음 확인: `grep -rn "Random" src/main/java`).

- [ ] **Step 8: 통과 확인 + 커밋**

Run: `./gradlew test --tests '*AdServeServiceTest*' --offline`
Expected: PASS (3 테스트).
```bash
git add src/main/java/ai/devpath/platform/ads/AdSlot.java src/main/java/ai/devpath/platform/ads/AdView.java src/main/java/ai/devpath/platform/ads/AdServeService.java src/main/java/ai/devpath/platform/ads/AdController.java src/main/java/ai/devpath/platform/ads/AdsConfig.java src/test/java/ai/devpath/platform/ads/AdServeServiceTest.java
git commit -m "feat(ads): 슬롯 서빙(가중치 랜덤)+GET /ads"
```

---

## Task 5: 측정 — AdDailyStats + AdEventService + POST /ads/{id}/events

**Files:**
- Create: `src/main/java/ai/devpath/platform/ads/AdDailyStats.java`
- Create: `src/main/java/ai/devpath/platform/ads/AdDailyStatsRepository.java`
- Create: `src/main/java/ai/devpath/platform/ads/AdEventType.java`
- Create: `src/main/java/ai/devpath/platform/ads/AdEventService.java`
- Create: `src/main/java/ai/devpath/platform/ads/AdNotFoundException.java`
- Modify: `src/main/java/ai/devpath/platform/ads/AdController.java` (POST 추가)
- Test: `src/test/java/ai/devpath/platform/ads/AdEventServiceTest.java`

**Interfaces:**
- Consumes: `AdvertisementRepository`.
- Produces: `AdEventService.record(long adId, String type)` → `void`(광고 없으면 AdNotFoundException); `AdDailyStatsRepository.upsertImpression(long adId, LocalDate date)` / `upsertClick(...)`.

- [ ] **Step 1: 실패 테스트 작성**

`AdEventServiceTest.java`:
```java
package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdEventServiceTest {

  @Autowired AdEventService service;
  @Autowired AdvertisementRepository ads;
  @Autowired AdDailyStatsRepository stats;

  @Test
  void impressionUpsertsIncrement() {
    Advertisement a = ads.save(newAd());
    service.record(a.getId(), "IMPRESSION");
    service.record(a.getId(), "IMPRESSION");
    var today = java.time.LocalDate.now(ZoneOffset.UTC);
    assertThat(stats.findById(new AdDailyStats.Key(a.getId(), today)))
        .get().extracting(AdDailyStats::getImpressions).isEqualTo(2L);
  }

  @Test
  void clickUpsertsIncrement() {
    Advertisement a = ads.save(newAd());
    service.record(a.getId(), "CLICK");
    var today = java.time.LocalDate.now(ZoneOffset.UTC);
    assertThat(stats.findById(new AdDailyStats.Key(a.getId(), today)))
        .get().extracting(AdDailyStats::getClicks).isEqualTo(1L);
  }

  @Test
  void unknownAdThrowsNotFound() {
    assertThatThrownBy(() -> service.record(999999L, "IMPRESSION"))
        .isInstanceOf(AdNotFoundException.class);
  }

  private Advertisement newAd() {
    Advertisement a = new Advertisement();
    a.setTitle("t");
    a.setLinkUrl("https://e.com");
    a.setSlot("DASHBOARD_TOP");
    a.setWeight(1);
    a.setStatus("ACTIVE");
    return a;
  }
}
```
> Step 1의 `import java.time.Localate`는 오타 — Step 3 구현 전 `java.time.LocalDate`로 고친다. 테스트는 `@Transactional`이라 UPSERT가 같은 트랜잭션에서 보이려면 native 쿼리 후 `flush`가 필요할 수 있음 → 리포지토리 upsert에 `@Modifying(clearAutomatically=true, flushAutomatically=true)` 부여(Step 4).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*AdEventServiceTest*' --offline`
Expected: 컴파일 실패.

- [ ] **Step 3: AdDailyStats 엔티티(복합키) 구현**

`AdDailyStats.java`:
```java
package ai.devpath.platform.ads;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "ad_daily_stats")
@IdClass(AdDailyStats.Key.class)
public class AdDailyStats {
  @Id
  @Column(name = "ad_id")
  private Long adId;

  @Id
  @Column(name = "stat_date")
  private LocalDate statDate;

  private long impressions;
  private long clicks;

  public Long getAdId() { return adId; }
  public LocalDate getStatDate() { return statDate; }
  public long getImpressions() { return impressions; }
  public long getClicks() { return clicks; }

  public static class Key implements Serializable {
    private Long adId;
    private LocalDate statDate;
    public Key() {}
    public Key(Long adId, LocalDate statDate) { this.adId = adId; this.statDate = statDate; }
    @Override public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Key k)) return false;
      return Objects.equals(adId, k.adId) && Objects.equals(statDate, k.statDate);
    }
    @Override public int hashCode() { return Objects.hash(adId, statDate); }
  }
}
```

- [ ] **Step 4: AdDailyStatsRepository (native UPSERT) 구현**

`AdDailyStatsRepository.java`:
```java
package ai.devpath.platform.ads;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdDailyStatsRepository extends JpaRepository<AdDailyStats, AdDailyStats.Key> {

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(value = "INSERT INTO ad_daily_stats (ad_id, stat_date, impressions, clicks) "
      + "VALUES (:adId, :date, 1, 0) "
      + "ON CONFLICT (ad_id, stat_date) DO UPDATE SET impressions = ad_daily_stats.impressions + 1",
      nativeQuery = true)
  void upsertImpression(@Param("adId") long adId, @Param("date") LocalDate date);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(value = "INSERT INTO ad_daily_stats (ad_id, stat_date, impressions, clicks) "
      + "VALUES (:adId, :date, 0, 1) "
      + "ON CONFLICT (ad_id, stat_date) DO UPDATE SET clicks = ad_daily_stats.clicks + 1",
      nativeQuery = true)
  void upsertClick(@Param("adId") long adId, @Param("date") LocalDate date);
}
```

- [ ] **Step 5: AdEventType + AdNotFoundException 구현**

`AdEventType.java`:
```java
package ai.devpath.platform.ads;

public enum AdEventType {
  IMPRESSION,
  CLICK;

  public static AdEventType parse(String v) {
    try {
      return AdEventType.valueOf(v);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IllegalArgumentException("유효하지 않은 이벤트 타입: " + v);
    }
  }
}
```
`AdNotFoundException.java`:
```java
package ai.devpath.platform.ads;

import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.error.ErrorCode;

public class AdNotFoundException extends ApiException {
  public AdNotFoundException(long id) {
    super(ErrorCode.RESOURCE_NOT_FOUND, "광고 없음: " + id);
  }
}
```

- [ ] **Step 6: AdEventService 구현**

`AdEventService.java`:
```java
package ai.devpath.platform.ads;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdEventService {

  private final AdvertisementRepository ads;
  private final AdDailyStatsRepository stats;

  public AdEventService(AdvertisementRepository ads, AdDailyStatsRepository stats) {
    this.ads = ads;
    this.stats = stats;
  }

  @Transactional
  public void record(long adId, String type) {
    if (!ads.existsById(adId)) {
      throw new AdNotFoundException(adId);
    }
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    switch (AdEventType.parse(type)) {
      case IMPRESSION -> stats.upsertImpression(adId, today);
      case CLICK -> stats.upsertClick(adId, today);
    }
  }
}
```

- [ ] **Step 7: AdController에 POST 추가**

`AdController.java`에 추가(기존 import에 더해 `PathVariable`, `PostMapping`, `RequestBody`, `HttpStatus`, `ResponseStatus` 사용). 클래스에 `AdEventService` 주입 추가:
```java
  // 필드/생성자에 AdEventService eventService 추가

  public record EventRequest(String type) {}

  @org.springframework.web.bind.annotation.PostMapping("/ads/{id}/events")
  @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public void event(
      @org.springframework.web.bind.annotation.PathVariable long id,
      @org.springframework.web.bind.annotation.RequestBody EventRequest body) {
    eventService.record(id, body.type());
  }
```
> AdController 생성자를 `(AdServeService serve, AdEventService eventService)`로 확장하고 필드 저장.

- [ ] **Step 8: 통과 확인 + 커밋**

Run: `./gradlew test --tests '*AdEventServiceTest*' --offline`
Expected: PASS (3 테스트).
```bash
git add src/main/java/ai/devpath/platform/ads/AdDailyStats.java src/main/java/ai/devpath/platform/ads/AdDailyStatsRepository.java src/main/java/ai/devpath/platform/ads/AdEventType.java src/main/java/ai/devpath/platform/ads/AdNotFoundException.java src/main/java/ai/devpath/platform/ads/AdEventService.java src/main/java/ai/devpath/platform/ads/AdController.java src/test/java/ai/devpath/platform/ads/AdEventServiceTest.java
git commit -m "feat(ads): 노출/클릭 이벤트 UPSERT+POST /ads/{id}/events"
```

---

## Task 6: Admin CRUD — AdAdminService + AdminAdController

**Files:**
- Create: `src/main/java/ai/devpath/platform/ads/dto/AdRequest.java`
- Create: `src/main/java/ai/devpath/platform/ads/dto/AdRow.java`
- Create: `src/main/java/ai/devpath/platform/ads/AdAdminService.java`
- Create: `src/main/java/ai/devpath/platform/ads/AdminAdController.java`
- Test: `src/test/java/ai/devpath/platform/ads/AdAdminServiceTest.java`

**Interfaces:**
- Consumes: `AdvertisementRepository`.
- Produces: `AdAdminService.create(AdRequest)`→`AdRow`, `update(long, AdRequest)`→`AdRow`, `delete(long)`→`void`, `list(String slot, String status)`→`List<AdRow>`. `AdRequest`(record: title,imageUrl,linkUrl,slot,weight,status,startsAt,endsAt), `AdRow`(전 필드 record).

- [ ] **Step 1: 실패 테스트 작성**

`AdAdminServiceTest.java`:
```java
package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.devpath.platform.ads.dto.AdRequest;
import ai.devpath.platform.ads.dto.AdRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdAdminServiceTest {

  @Autowired AdAdminService service;

  @Test
  void createThenListReturnsRow() {
    AdRow row = service.create(new AdRequest("배너", null, "https://e.com", "DASHBOARD_TOP", 2, "ACTIVE", null, null));
    assertThat(row.id()).isNotNull();
    assertThat(service.list(null, null)).extracting(AdRow::title).contains("배너");
  }

  @Test
  void createRejectsBlankTitle() {
    assertThatThrownBy(() -> service.create(new AdRequest(" ", null, "https://e.com", "DASHBOARD_TOP", 1, "ACTIVE", null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsInvalidSlot() {
    assertThatThrownBy(() -> service.create(new AdRequest("t", null, "https://e.com", "NOPE", 1, "ACTIVE", null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsWeightBelowOne() {
    assertThatThrownBy(() -> service.create(new AdRequest("t", null, "https://e.com", "DASHBOARD_TOP", 0, "ACTIVE", null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*AdAdminServiceTest*' --offline`
Expected: 컴파일 실패.

- [ ] **Step 3: DTO 구현**

`dto/AdRequest.java`:
```java
package ai.devpath.platform.ads.dto;

import java.time.Instant;

public record AdRequest(
    String title, String imageUrl, String linkUrl, String slot,
    int weight, String status, Instant startsAt, Instant endsAt) {}
```
`dto/AdRow.java`:
```java
package ai.devpath.platform.ads.dto;

import ai.devpath.platform.ads.Advertisement;
import java.time.Instant;

public record AdRow(
    Long id, String title, String imageUrl, String linkUrl, String slot,
    int weight, String status, Instant startsAt, Instant endsAt) {
  public static AdRow of(Advertisement a) {
    return new AdRow(a.getId(), a.getTitle(), a.getImageUrl(), a.getLinkUrl(), a.getSlot(),
        a.getWeight(), a.getStatus(), a.getStartsAt(), a.getEndsAt());
  }
}
```

- [ ] **Step 4: AdAdminService 구현 (검증 포함)**

`AdAdminService.java`:
```java
package ai.devpath.platform.ads;

import ai.devpath.platform.ads.dto.AdRequest;
import ai.devpath.platform.ads.dto.AdRow;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdAdminService {

  private static final Set<String> STATUSES = Set.of("ACTIVE", "PAUSED");
  private final AdvertisementRepository repo;

  public AdAdminService(AdvertisementRepository repo) {
    this.repo = repo;
  }

  @Transactional
  public AdRow create(AdRequest req) {
    Advertisement a = new Advertisement();
    apply(a, req);
    return AdRow.of(repo.save(a));
  }

  @Transactional
  public AdRow update(long id, AdRequest req) {
    Advertisement a = repo.findById(id).orElseThrow(() -> new AdNotFoundException(id));
    apply(a, req);
    return AdRow.of(repo.save(a));
  }

  @Transactional
  public void delete(long id) {
    if (!repo.existsById(id)) {
      throw new AdNotFoundException(id);
    }
    repo.deleteById(id); // ad_daily_stats는 FK ON DELETE CASCADE
  }

  @Transactional(readOnly = true)
  public List<AdRow> list(String slot, String status) {
    return repo.findAll().stream()
        .filter(a -> slot == null || a.getSlot().equals(slot))
        .filter(a -> status == null || a.getStatus().equals(status))
        .map(AdRow::of)
        .toList();
  }

  private void apply(Advertisement a, AdRequest req) {
    if (req.title() == null || req.title().isBlank()) {
      throw new IllegalArgumentException("title은 필수입니다");
    }
    if (req.linkUrl() == null || req.linkUrl().isBlank()) {
      throw new IllegalArgumentException("linkUrl은 필수입니다");
    }
    if (req.weight() < 1) {
      throw new IllegalArgumentException("weight는 1 이상이어야 합니다");
    }
    if (!STATUSES.contains(req.status())) {
      throw new IllegalArgumentException("유효하지 않은 status: " + req.status());
    }
    a.setTitle(req.title());
    a.setImageUrl(req.imageUrl());
    a.setLinkUrl(req.linkUrl());
    a.setSlot(AdSlot.parse(req.slot()));
    a.setWeight(req.weight());
    a.setStatus(req.status());
    a.setStartsAt(req.startsAt());
    a.setEndsAt(req.endsAt());
  }
}
```

- [ ] **Step 5: AdminAdController 구현**

`AdminAdController.java`:
```java
package ai.devpath.platform.ads;

import ai.devpath.platform.ads.dto.AdRequest;
import ai.devpath.platform.ads.dto.AdRow;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** /admin/ads/** — SecurityConfig의 /admin/** hasRole("ADMIN")로 보호됨. */
@RestController
@RequestMapping("/admin/ads")
public class AdminAdController {

  private final AdAdminService service;

  public AdminAdController(AdAdminService service) {
    this.service = service;
  }

  @GetMapping
  public List<AdRow> list(@RequestParam(required = false) String slot,
      @RequestParam(required = false) String status) {
    return service.list(slot, status);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AdRow create(@RequestBody AdRequest req) {
    return service.create(req);
  }

  @PutMapping("/{id}")
  public AdRow update(@PathVariable long id, @RequestBody AdRequest req) {
    return service.update(id, req);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable long id) {
    service.delete(id);
  }
}
```

- [ ] **Step 6: 통과 확인 + 커밋**

Run: `./gradlew test --tests '*AdAdminServiceTest*' --offline`
Expected: PASS (4 테스트).
```bash
git add src/main/java/ai/devpath/platform/ads/dto/AdRequest.java src/main/java/ai/devpath/platform/ads/dto/AdRow.java src/main/java/ai/devpath/platform/ads/AdAdminService.java src/main/java/ai/devpath/platform/ads/AdminAdController.java src/test/java/ai/devpath/platform/ads/AdAdminServiceTest.java
git commit -m "feat(ads): admin CRUD 서비스+/admin/ads"
```

---

## Task 7: Admin 이미지 업로드 (기존 스토리지 재사용)

**Files:**
- Create: `src/main/java/ai/devpath/platform/ads/AdImageService.java`
- Modify: `src/main/java/ai/devpath/platform/ads/AdminAdController.java` (POST /{id}/image)
- Test: `src/test/java/ai/devpath/platform/ads/AdImageServiceTest.java`

**Interfaces:**
- Consumes: `AdvertisementRepository`, `ObjectProvider<ObjectStorage>`, `ObjectProvider<StoredFileValidator>` (shared).
- Produces: `AdImageService.upload(long id, byte[] content, String contentType, String filename)` → `AdRow`.

- [ ] **Step 1: 실패 테스트 작성 (스토리지 미구성 → 503)**

`AdImageServiceTest.java`:
```java
package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.storage.ObjectStorage;
import ai.devpath.shared.storage.StoredFileValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AdImageServiceTest {

  private final AdvertisementRepository repo = mock(AdvertisementRepository.class);

  @Test
  void uploadWithoutStorageThrows503() {
    @SuppressWarnings("unchecked")
    ObjectProvider<ObjectStorage> storage = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<StoredFileValidator> validator = mock(ObjectProvider.class);
    when(storage.getIfAvailable()).thenReturn(null);
    when(validator.getIfAvailable()).thenReturn(null);

    Advertisement a = new Advertisement();
    a.setTitle("t"); a.setLinkUrl("https://e.com"); a.setSlot("DASHBOARD_TOP"); a.setStatus("ACTIVE"); a.setWeight(1);
    when(repo.findById(1L)).thenReturn(java.util.Optional.of(a));

    AdImageService svc = new AdImageService(repo, storage, validator);
    assertThatThrownBy(() -> svc.upload(1L, new byte[]{1}, "image/png", "x.png"))
        .isInstanceOf(ApiException.class);
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*AdImageServiceTest*' --offline`
Expected: 컴파일 실패.

- [ ] **Step 3: AdImageService 구현 (AvatarService 패턴)**

`AdImageService.java`:
```java
package ai.devpath.platform.ads;

import ai.devpath.platform.ads.dto.AdRow;
import ai.devpath.shared.storage.ObjectStorage;
import ai.devpath.shared.storage.ObjectStorage.StoredObject;
import ai.devpath.shared.storage.StorageException;
import ai.devpath.shared.storage.StoredFileValidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 광고 소재 이미지 업로드. 스토리지 미구성 시 503(StorageException). */
@Service
public class AdImageService {

  private final AdvertisementRepository ads;
  private final ObjectProvider<ObjectStorage> storageProvider;
  private final ObjectProvider<StoredFileValidator> validatorProvider;

  public AdImageService(AdvertisementRepository ads,
      ObjectProvider<ObjectStorage> storageProvider,
      ObjectProvider<StoredFileValidator> validatorProvider) {
    this.ads = ads;
    this.storageProvider = storageProvider;
    this.validatorProvider = validatorProvider;
  }

  @Transactional
  public AdRow upload(long id, byte[] content, String contentType, String filename) {
    Advertisement a = ads.findById(id).orElseThrow(() -> new AdNotFoundException(id));
    ObjectStorage storage = storage();
    validator().validate(contentType, content.length);
    String key = validator().key("ads", filename);
    StoredObject stored = storage.put(key, content, contentType);
    a.setImageUrl(stored.url());
    return AdRow.of(ads.save(a));
  }

  private ObjectStorage storage() {
    ObjectStorage s = storageProvider.getIfAvailable();
    if (s == null) {
      throw new StorageException("스토리지가 구성되지 않았습니다");
    }
    return s;
  }

  private StoredFileValidator validator() {
    StoredFileValidator v = validatorProvider.getIfAvailable();
    if (v == null) {
      throw new StorageException("스토리지가 구성되지 않았습니다");
    }
    return v;
  }
}
```
> `StoredFileValidator.key(prefix, filename)`·`validate(contentType, length)`·`ObjectStorage.put(key, bytes, contentType)`→`StoredObject.url()` 시그니처는 AvatarService와 동일(shared). 실제 시그니처는 `AvatarService.java`로 재확인.

- [ ] **Step 4: 컨트롤러에 업로드 엔드포인트 추가**

`AdminAdController.java`에 `AdImageService imageService` 주입 추가 후:
```java
  @PostMapping("/{id}/image")
  public AdRow uploadImage(@PathVariable long id,
      @RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
    return imageService.upload(id, file.getBytes(), file.getContentType(), file.getOriginalFilename());
  }
```

- [ ] **Step 5: 통과 확인 + 커밋**

Run: `./gradlew test --tests '*AdImageServiceTest*' --offline`
Expected: PASS.
```bash
git add src/main/java/ai/devpath/platform/ads/AdImageService.java src/main/java/ai/devpath/platform/ads/AdminAdController.java src/test/java/ai/devpath/platform/ads/AdImageServiceTest.java
git commit -m "feat(ads): admin 광고 이미지 업로드(스토리지 재사용)"
```

---

## Task 8: Admin 전역 토글 + 통계 엔드포인트

**Files:**
- Create: `src/main/java/ai/devpath/platform/ads/dto/AdSettingsView.java`
- Create: `src/main/java/ai/devpath/platform/ads/dto/AdStatsRow.java`
- Create: `src/main/java/ai/devpath/platform/ads/AdStatsService.java`
- Modify: `src/main/java/ai/devpath/platform/ads/AdminAdController.java`
- Modify: `src/main/java/ai/devpath/platform/ads/AdDailyStatsRepository.java` (범위 조회)
- Test: `src/test/java/ai/devpath/platform/ads/AdStatsServiceTest.java`

**Interfaces:**
- Consumes: `AdSettingsService`, `AdDailyStatsRepository`.
- Produces: `AdStatsService.stats(long adId, LocalDate from, LocalDate to)`→`List<AdStatsRow>`; `AdSettingsView`(record boolean enabled); `AdStatsRow`(record LocalDate date, long impressions, long clicks).

- [ ] **Step 1: 실패 테스트 작성**

`AdStatsServiceTest.java`:
```java
package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.platform.ads.dto.AdStatsRow;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdStatsServiceTest {

  @Autowired AdStatsService service;
  @Autowired AdEventService events;
  @Autowired AdvertisementRepository ads;

  @Test
  void statsReturnsDailyCounts() {
    Advertisement a = new Advertisement();
    a.setTitle("t"); a.setLinkUrl("https://e.com"); a.setSlot("DASHBOARD_TOP"); a.setStatus("ACTIVE"); a.setWeight(1);
    a = ads.save(a);
    events.record(a.getId(), "IMPRESSION");
    events.record(a.getId(), "CLICK");

    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    List<AdStatsRow> rows = service.stats(a.getId(), today.minusDays(1), today.plusDays(1));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).impressions()).isEqualTo(1L);
    assertThat(rows.get(0).clicks()).isEqualTo(1L);
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*AdStatsServiceTest*' --offline`
Expected: 컴파일 실패.

- [ ] **Step 3: 리포지토리 범위 조회 추가**

`AdDailyStatsRepository.java`에 추가:
```java
  java.util.List<AdDailyStats> findByAdIdAndStatDateBetweenOrderByStatDate(
      long adId, java.time.LocalDate from, java.time.LocalDate to);
```

- [ ] **Step 4: DTO 구현**

`dto/AdSettingsView.java`:
```java
package ai.devpath.platform.ads.dto;

public record AdSettingsView(boolean enabled) {}
```
`dto/AdStatsRow.java`:
```java
package ai.devpath.platform.ads.dto;

import ai.devpath.platform.ads.AdDailyStats;
import java.time.LocalDate;

public record AdStatsRow(LocalDate date, long impressions, long clicks) {
  public static AdStatsRow of(AdDailyStats s) {
    return new AdStatsRow(s.getStatDate(), s.getImpressions(), s.getClicks());
  }
}
```

- [ ] **Step 5: AdStatsService 구현**

`AdStatsService.java`:
```java
package ai.devpath.platform.ads;

import ai.devpath.platform.ads.dto.AdStatsRow;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdStatsService {

  private final AdDailyStatsRepository stats;

  public AdStatsService(AdDailyStatsRepository stats) {
    this.stats = stats;
  }

  @Transactional(readOnly = true)
  public List<AdStatsRow> stats(long adId, LocalDate from, LocalDate to) {
    return stats.findByAdIdAndStatDateBetweenOrderByStatDate(adId, from, to).stream()
        .map(AdStatsRow::of)
        .toList();
  }
}
```

- [ ] **Step 6: 컨트롤러에 settings·stats 추가**

`AdminAdController.java`에 `AdSettingsService settingsService`, `AdStatsService statsService` 주입 후:
```java
  @GetMapping("/settings")
  public ai.devpath.platform.ads.dto.AdSettingsView settings() {
    return new ai.devpath.platform.ads.dto.AdSettingsView(settingsService.isEnabled());
  }

  @PutMapping("/settings")
  public ai.devpath.platform.ads.dto.AdSettingsView updateSettings(
      @RequestBody ai.devpath.platform.ads.dto.AdSettingsView body) {
    settingsService.setEnabled(body.enabled());
    return new ai.devpath.platform.ads.dto.AdSettingsView(settingsService.isEnabled());
  }

  @GetMapping("/{id}/stats")
  public java.util.List<ai.devpath.platform.ads.dto.AdStatsRow> stats(
      @PathVariable long id,
      @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
      @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
    return statsService.stats(id, from, to);
  }
```

- [ ] **Step 7: 통과 확인 + 커밋**

Run: `./gradlew test --tests '*AdStatsServiceTest*' --offline`
Expected: PASS.
```bash
git add src/main/java/ai/devpath/platform/ads/dto/AdSettingsView.java src/main/java/ai/devpath/platform/ads/dto/AdStatsRow.java src/main/java/ai/devpath/platform/ads/AdStatsService.java src/main/java/ai/devpath/platform/ads/AdDailyStatsRepository.java src/main/java/ai/devpath/platform/ads/AdminAdController.java src/test/java/ai/devpath/platform/ads/AdStatsServiceTest.java
git commit -m "feat(ads): admin 전역 토글+통계 엔드포인트"
```

---

## Task 9: 게이트웨이 라우트 /ads/**

**Files:**
- Modify: `devpath-gateway/src/main/resources/application.yml`

**Interfaces:**
- Produces: `/ads/**` → platform(:8081) 라우팅. (admin은 기존 `/admin/**`→platform 라우트가 커버하는지 확인; 없으면 추가.)

- [ ] **Step 1: 기존 라우트 확인**

Run: `grep -nE "id:|uri:|Path=" devpath-gateway/src/main/resources/application.yml`
Expected: platform-auth 라우트가 `/users/**,/admin/**,...`를 포함하는지 확인. `/admin/**`이 이미 platform으로 가면 admin 광고도 커버됨. `/ads/**`는 없으므로 추가 필요.

- [ ] **Step 2: /ads 라우트 추가**

`platform-auth` 라우트의 `predicates.Path`에 `/ads/**` 추가(가장 단순), 또는 신규 라우트:
```yaml
            - id: ads
              uri: ${PLATFORM_URI:http://localhost:8081}
              predicates:
                - Path=/ads/**
```
> 만약 `/admin/**`이 platform-auth Path에 없으면 그것도 함께 추가한다(admin 광고 라우팅).

- [ ] **Step 3: 렌더 검증**

Run: `cd devpath-gateway && ./gradlew compileJava --offline` (YAML 파싱 확인은 부팅으로)
로컬 부팅 후: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ads?slot=DASHBOARD_TOP` (토큰 없이 401 예상 = 라우팅+보안 동작).

- [ ] **Step 4: 커밋**

```bash
cd devpath-gateway && git add src/main/resources/application.yml && git commit -m "feat(ads): /ads 게이트웨이 라우트"
```
> gateway도 별도 브랜치(`feat/ads-route`)에서 develop PR.

---

## 통합 검증 (전 Task 후)

- [ ] platform 전체 테스트: `cd devpath-platform-svc && ./gradlew test --offline` (기존 25 + 신규 ads 테스트 그린)
- [ ] 로컬 e2e 스모크: 인프라+platform+gateway 기동 → admin JWT로 `POST /admin/ads`(생성)·`PUT /admin/ads/settings {enabled:true}` → 유저 JWT로 `GET /ads?slot=DASHBOARD_TOP`(200 광고)·`POST /ads/{id}/events {type:IMPRESSION}`(202) → `GET /admin/ads/{id}/stats?from=&to=`(노출 1).

---

## Self-Review

**1. Spec coverage:** 스키마 3테이블+시드(Task1) / 서빙 가중치·204(Task4) / 이벤트 UPSERT·202·404(Task5) / admin CRUD·검증(Task6) / 이미지 업로드·503(Task7) / 전역 토글·통계(Task8) / 게이트웨이(Task9) / 티어 predicate 스텁(Task4 userShouldSeeAds) / hikari 캡(Task1) / SecurityConfig 무변경(Global Constraints 명시). 스펙 P1 전 항목 커버.

**2. Placeholder scan:** TODO/TBD 없음. 모든 Step에 실제 코드/SQL/명령 포함. (초안의 오타 2곳은 정정 완료.)

**3. Type consistency:** `Advertisement` getter/setter, `AdView.of`, `AdRow.of`, `AdDailyStats.Key`, `AdSettingsService.isEnabled/setEnabled`, `AdEventService.record`, `AdAdminService.create/update/delete/list`, `AdImageService.upload`, `AdStatsService.stats` — Task 간 시그니처 일치. `AdSlot.parse`는 Task4 정의·Task6 재사용. 컨트롤러 주입은 Task5·7·8에서 `AdController`/`AdminAdController` 생성자 확장 명시.

**리스크:** ①shared 마이그레이션은 별도 브랜치→PR→publish 필요(platform 빌드가 새 스키마 의존). ②`set_updated_at()` 함수 존재를 Task1 Step2에서 확인 후 SQL 확정. ③native UPSERT는 `@Transactional` 테스트에서 flush 필요(`flushAutomatically=true` 부여). ④`StoredFileValidator`/`ObjectStorage` 시그니처는 AvatarService로 재확인.
