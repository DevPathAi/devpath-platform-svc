# 구글 애드센스 병행 도입 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 광고 슬롯 3곳을 admin에서 하우스 광고 / 구글 애드센스 / 끄기 중 하나로 지정할 수 있게 하고, 애드센스로 지정된 슬롯은 실제 애드센스 광고 단위를 렌더한다.

**Architecture:** 슬롯별 소스를 `ad_slot_config` 테이블로 관리하고, 기존 `GET /ads?slot=` 응답을 `{"type":"HOUSE"|"ADSENSE", …}` 판별 유니온 봉투로 확장한다. 프론트는 Monaco 에디터가 이미 쓰고 있는 `HtmlElementView` + `platformViewRegistry` + 조건부 import 패턴으로 `<ins class="adsbygoogle">`를 캔버스 안에 삽입한다. 광고가 채워지지 않으면 접는다.

**Tech Stack:** Java 21 · Spring Boot 4.0.7 · JPA · Flyway (shared 중앙 마이그레이션) · Flutter Web · Riverpod 3 · `dart:js_interop` / `dart:ui_web`

**설계 스펙:** [`docs/superpowers/specs/2026-08-09-adsense-integration-design.md`](../specs/2026-08-09-adsense-integration-design.md)

## Global Constraints

- **착수 전 필수 입력값:** 구글 애드센스 퍼블리셔 ID(`ca-pub-` + 숫자 16자리). Task 8에서 `apps/web/web/index.html`과 `apps/web/web/ads.txt` 두 곳에 들어간다. 값이 없으면 Task 8을 시작하지 말고 사용자에게 요청한다. 공개 값이므로 커밋해도 된다.
- **애드센스 광고 단위에 자체 노출·클릭 추적을 붙이지 않는다.** 구글 정책이 광고 클릭 개입과 인위적 노출 부풀리기를 금지한다. `adEventProvider`·`VisibilityDetector`·`ad_daily_stats`는 하우스 광고 전용이다.
- **슬롯 문자열은 정확히 3개:** `DASHBOARD_TOP` · `COMMUNITY_FEED` · `CONTENT_PAGE`.
- **소스 문자열은 정확히 3개:** `HOUSE` · `ADSENSE` · `OFF`.
- **`source=ADSENSE`인데 `adsenseSlotId`가 비어 있어도 저장을 허용한다.** 서빙에서 204로 접는 것이 정의된 동작이며, 400을 던지면 그 분기가 죽은 코드가 된다.
- **모든 레포가 Test-First다.** 실패하는 테스트를 먼저 쓰고, 실패를 눈으로 확인한 뒤 최소 구현을 한다.
- **모든 작업은 `develop`에서 분기한 새 브랜치에서 한다.** `main`·`develop`에 직접 push 금지.
- **`git` 명령에는 항상 `-C <레포 절대경로>`를 쓴다.** `cd` 후 상대경로 금지.
- **배포 순서: 백엔드(platform-svc) → 프론트(web).** `GET /ads` 응답이 `{id,…}`에서 `{type,ad}` 봉투로 바뀌므로 한쪽만 배포된 구간에서는 광고가 조용히 사라진다(양방향 모두 fail-silent라 크래시는 없다). 지금은 AWS 정지로 즉시 영향이 없지만 재가동 시 이 순서를 지킨다.

### 착수 시 사용자가 인지하고 수용한 위험 (2026-08-10)

착수 전 검토에서 아래를 보고했고, 사용자가 진행을 지시했다. **구현 결함이 아니라 사업 판단 사항**이므로 이 계획은 그대로 진행하되 기대치를 여기 명시해 둔다.

- **애드센스 크롤러가 광고 게재 화면에 도달할 수 없다.** 슬롯 3곳(`dashboard_body.dart:44` · `community_home_page.dart:336` · `content_page.dart:342`)은 전부 `router.dart:84`의 `gateRedirect` 뒤, 즉 로그인 + 베타 허용리스트 통과 후 화면이다. 게다가 CanvasKit은 텍스트를 캔버스에 그려 크롤러가 읽을 콘텐츠가 사실상 없다. → **코드가 완벽해도 심사가 통과되지 않으면 광고는 나오지 않는다.** 심사 대응(공개 랜딩 콘텐츠 확보 등)은 이 계획의 범위 밖이며 별건으로 다룬다.

## 레포별 브랜치와 PR

| 순서 | 레포 | 브랜치 | Task |
|---|---|---|---|
| 1 | `devpath-shared` | `feat/ad-slot-config-schema` | Task 1 |
| 2 | `devpath-platform-svc` | `feat/adsense-slot-config` | Task 2 ~ 5 |
| 3 | `devpath-frontend` | `feat/adsense-integration` | Task 6 ~ 10 |
| 4 | — (검증 전용) | — | Task 11 |

**임계경로:** platform-svc의 `@SpringBootTest`는 shared jar의 `classpath:db/migration`으로 테스트 DB 스키마를 만든다(`src/test/resources/application-test.yml`의 `spring.flyway.locations`). 그리고 platform-svc의 `repositories`에는 `mavenLocal()`이 **없다**(`mavenCentral()` + GitHub Packages만). 따라서 **Task 1이 머지·발행되기 전에는 Task 2~5의 테스트가 `ad_slot_config` 테이블을 볼 수 없다.** Task 1 종료 절차에 발행이 포함돼 있다.

## File Structure

**devpath-shared**
- Create: `src/main/resources/db/migration/V202608091001__ad_slot_config.sql` — 슬롯별 소스 설정 테이블과 3행 시드
- Modify: `src/test/java/ai/devpath/shared/db/FlywayMigrationTest.java` — 테이블·시드 존재 검증 추가

**devpath-platform-svc** (`src/main/java/ai/devpath/platform/ads/`)
- Create: `AdSlotSource.java` — 소스 문자열 카탈로그와 검증 (`AdSlot.java`와 같은 형태)
- Create: `AdSlotConfig.java` — `ad_slot_config` 엔티티
- Create: `AdSlotConfigRepository.java`
- Create: `AdSlotConfigService.java` — 조회·갱신, 문자열 정규화
- Create: `AdSlotContent.java` — sealed interface (`House` / `Adsense`)
- Create: `dto/AdSlotConfigView.java` · `dto/AdSlotConfigRequest.java` · `dto/AdServeResponse.java`
- Modify: `AdServeService.java` — 슬롯 소스 분기, 반환 타입 변경
- Modify: `AdController.java` — 봉투 응답 조립
- Modify: `AdminAdController.java` — slot-config 엔드포인트 2개
- Modify: `src/test/java/ai/devpath/platform/ads/AdServeServiceTest.java` — 생성자 변경 + 신규 케이스
- Create: `src/test/java/ai/devpath/platform/ads/AdServeContractTest.java` — 봉투 계약 (신설. 기존 `AdControllerTest`는 이벤트 엔드포인트 전용이라 건드리지 않는다)
- Create: `src/test/java/ai/devpath/platform/ads/AdSlotConfigApiTest.java` — admin API

**devpath-frontend**
- Create: `apps/web/lib/src/features/ads/data/ad_slot_content.dart` — sealed 유니온과 파싱
- Modify: `apps/web/lib/src/features/ads/data/ads_source.dart` — `adFetchProvider` 반환 타입
- Create: `apps/web/lib/src/features/ads/presentation/adsense_unit_view.dart` (추상) · `_stub.dart` · `_web.dart`
- Modify: `apps/web/web/index.html` — 애드센스 스크립트와 `window.createDevpathAdUnit` 심
- Create: `apps/web/web/ads.txt`
- Modify: `apps/web/lib/src/features/ads/presentation/ad_slot_widget.dart` — 세 갈래 분기
- Modify: `apps/web/test/features/ads/ads_source_test.dart` · `ad_slot_widget_test.dart`
- Create: `apps/admin/lib/src/features/ads/data/ad_slot_config_row.dart`
- Modify: `apps/admin/lib/src/features/ads/data/ads_source.dart` · `state/ads_state.dart` · `application/ads_controller.dart` · `presentation/ads_page.dart`
- Modify: `apps/admin/test/features/ads/ads_page_test.dart`

---

## Task 1: shared — `ad_slot_config` 마이그레이션

**Files:**
- Create: `devpath-shared/src/main/resources/db/migration/V202608091001__ad_slot_config.sql`
- Test: `devpath-shared/src/test/java/ai/devpath/shared/db/FlywayMigrationTest.java`

**Interfaces:**
- Consumes: 없음 (첫 Task)
- Produces: `ad_slot_config` 테이블 — 컬럼 `slot`(PK, VARCHAR(30)) · `source`(VARCHAR(20), NOT NULL, DEFAULT `'HOUSE'`) · `adsense_slot_id`(VARCHAR(50), nullable) · `updated_at`(TIMESTAMPTZ). 3행 시드는 전부 `source='HOUSE'`.

> **사전 준비:** 이 레포의 `FlywayMigrationTest`는 **실제 Postgres에 연결한다**(기본 `jdbc:postgresql://localhost:5432/devpath`, 사용자 `devpath`/`localdev`). 시작 전 `docker compose up -d`로 로컬 인프라를 띄운다.

- [ ] **Step 1: 브랜치 분기**

```bash
git -C /d/workspace/dpa/devpath-shared fetch origin
git -C /d/workspace/dpa/devpath-shared switch -c feat/ad-slot-config-schema origin/develop
```

- [ ] **Step 2: 실패하는 테스트 작성**

`FlywayMigrationTest.java`에 아래 테스트를 추가한다(기존 테스트들과 같은 스타일 — 매번 `migrate()`를 호출해 멱등하게 통과시킨다).

```java
  @Test
  void adSlotConfigTableExistsWithThreeHouseSeedRows() throws Exception {
    Flyway.configure().dataSource(dataSource())
        .locations("classpath:db/migration").load().migrate();
    try (var c = dataSource().getConnection();
        var st = c.createStatement();
        var rs = st.executeQuery(
            "SELECT slot, source, adsense_slot_id FROM ad_slot_config ORDER BY slot")) {
      var seen = new java.util.LinkedHashMap<String, String>();
      while (rs.next()) {
        seen.put(rs.getString("slot"), rs.getString("source"));
        assertNull(rs.getString("adsense_slot_id"), "시드는 단위 ID가 비어 있어야 한다");
      }
      assertEquals(3, seen.size(), "슬롯 3행이 시드돼야 한다");
      assertEquals("HOUSE", seen.get("DASHBOARD_TOP"));
      assertEquals("HOUSE", seen.get("COMMUNITY_FEED"));
      assertEquals("HOUSE", seen.get("CONTENT_PAGE"));
    }
  }
```

파일 상단 import에 아래 두 줄을 추가한다(기존에 `assertTrue`·`assertFalse`·`assertThrows`만 있다).

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
```

- [ ] **Step 3: 테스트를 돌려 실패를 확인**

```bash
cd /d/workspace/dpa/devpath-shared && ./gradlew test --tests '*FlywayMigrationTest*'
```

기대: `adSlotConfigTableExistsWithThreeHouseSeedRows`가 **실패**한다 — `ERROR: relation "ad_slot_config" does not exist`.

- [ ] **Step 4: 마이그레이션 작성**

`src/main/resources/db/migration/V202608091001__ad_slot_config.sql`:

```sql
CREATE TABLE ad_slot_config (
    slot            VARCHAR(30) NOT NULL PRIMARY KEY,
    source          VARCHAR(20) NOT NULL DEFAULT 'HOUSE',
    adsense_slot_id VARCHAR(50),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ad_slot_config_slot   CHECK (slot   IN ('DASHBOARD_TOP','COMMUNITY_FEED','CONTENT_PAGE')),
    CONSTRAINT chk_ad_slot_config_source CHECK (source IN ('HOUSE','ADSENSE','OFF'))
);

INSERT INTO ad_slot_config (slot, source) VALUES
  ('DASHBOARD_TOP','HOUSE'),
  ('COMMUNITY_FEED','HOUSE'),
  ('CONTENT_PAGE','HOUSE');

CREATE TRIGGER ad_slot_config_set_updated_at BEFORE UPDATE ON ad_slot_config
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
```

- [ ] **Step 5: 테스트를 돌려 통과를 확인**

```bash
cd /d/workspace/dpa/devpath-shared && ./gradlew test --tests '*FlywayMigrationTest*'
```

기대: PASS. 이어서 전체 스위트도 돌린다.

```bash
cd /d/workspace/dpa/devpath-shared && ./gradlew test
```

기대: BUILD SUCCESSFUL. **`BUILD SUCCESSFUL`만으로 만족하지 말고** `build/test-results/test/`의 XML 건수로 실제 실행 여부를 확인한다.

- [ ] **Step 6: 커밋과 PR**

```bash
git -C /d/workspace/dpa/devpath-shared add src/main/resources/db/migration/V202608091001__ad_slot_config.sql src/test/java/ai/devpath/shared/db/FlywayMigrationTest.java
git -C /d/workspace/dpa/devpath-shared commit -m "feat(db): 광고 슬롯별 소스 설정 테이블을 추가한다"
git -C /d/workspace/dpa/devpath-shared push -u origin feat/ad-slot-config-schema
gh pr create --repo DevPathAi/devpath-shared --base develop --head feat/ad-slot-config-schema --title "feat(db): ad_slot_config 테이블 추가" --body "슬롯별 광고 소스(HOUSE/ADSENSE/OFF)와 애드센스 단위 ID를 관리하는 테이블. 시드 3행은 전부 HOUSE라 적용 직후 동작이 지금과 같다."
```

- [ ] **Step 7: CI 녹색 확인 후 머지**

```bash
gh pr checks --repo DevPathAi/devpath-shared feat/ad-slot-config-schema --watch
gh pr merge --repo DevPathAi/devpath-shared feat/ad-slot-config-schema --merge
```

- [ ] **Step 8: shared 발행 (이게 없으면 Task 2가 막힌다)**

`publish.yml`은 main push에만 자동 실행되므로 develop을 수동 발행한다.

```bash
gh workflow run publish.yml --repo DevPathAi/devpath-shared --ref develop
gh run list --repo DevPathAi/devpath-shared --workflow publish.yml --limit 1
```

발행 완료 후, platform-svc가 새 SNAPSHOT을 받는지 확인한다.

```bash
cd /d/workspace/dpa/devpath-platform-svc && ./gradlew --refresh-dependencies dependencies --configuration runtimeClasspath | grep devpath-shared
```

기대: `ai.devpath:devpath-shared:0.0.1-SNAPSHOT`이 해석된다.

---

## Task 2: platform-svc — 슬롯 설정 도메인

**Files:**
- Create: `src/main/java/ai/devpath/platform/ads/AdSlotSource.java`
- Create: `src/main/java/ai/devpath/platform/ads/AdSlotConfig.java`
- Create: `src/main/java/ai/devpath/platform/ads/AdSlotConfigRepository.java`
- Create: `src/main/java/ai/devpath/platform/ads/AdSlotConfigService.java`
- Test: `src/test/java/ai/devpath/platform/ads/AdSlotConfigServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `ad_slot_config` 테이블
- Produces:
  - `AdSlotSource.HOUSE` / `.ADSENSE` / `.OFF` (`String` 상수), `AdSlotSource.parse(String) → String` (유효하지 않으면 `IllegalArgumentException`)
  - `AdSlotConfig` 엔티티 — `getSlot()` · `getSource()` · `setSource(String)` · `getAdsenseSlotId()` · `setAdsenseSlotId(String)`
  - `AdSlotConfigService.get(String slot) → AdSlotConfig` (행이 없으면 `source=HOUSE`인 비영속 기본값 반환)
  - `AdSlotConfigService.list() → List<AdSlotConfig>` (slot 오름차순)
  - `AdSlotConfigService.update(String slot, String source, String adsenseSlotId) → AdSlotConfig`

- [ ] **Step 1: 브랜치 분기**

```bash
git -C /d/workspace/dpa/devpath-platform-svc fetch origin
git -C /d/workspace/dpa/devpath-platform-svc switch -c feat/adsense-slot-config origin/develop
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/ai/devpath/platform/ads/AdSlotConfigServiceTest.java`:

```java
package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AdSlotConfigServiceTest {

  @Autowired AdSlotConfigService service;

  /**
   * 이 테스트 DB는 여러 테스트 클래스가 공유한다. 각 테스트가 슬롯 설정을 바꾸므로
   * 매번 시드 상태로 되돌려 놓아야 실행 순서에 상관없이 결정적으로 통과한다.
   * (learning-svc에서 공유 DB 오염으로 인접 테스트가 깨진 전례가 있다.)
   */
  @AfterEach
  void restoreSeed() {
    for (String slot : new String[] {"DASHBOARD_TOP", "COMMUNITY_FEED", "CONTENT_PAGE"}) {
      service.update(slot, "HOUSE", null);
    }
  }

  @Test
  void listReturnsThreeSlotsInAscendingOrder() {
    var rows = service.list();
    assertThat(rows).hasSize(3);
    assertThat(rows.stream().map(AdSlotConfig::getSlot))
        .containsExactly("COMMUNITY_FEED", "CONTENT_PAGE", "DASHBOARD_TOP");
  }

  @Test
  void updateStoresSourceAndNormalizesBlankSlotIdToNull() {
    service.update("DASHBOARD_TOP", "ADSENSE", "  1234567890  ");
    assertThat(service.get("DASHBOARD_TOP").getSource()).isEqualTo("ADSENSE");
    assertThat(service.get("DASHBOARD_TOP").getAdsenseSlotId()).isEqualTo("1234567890");

    service.update("DASHBOARD_TOP", "ADSENSE", "   ");
    assertThat(service.get("DASHBOARD_TOP").getAdsenseSlotId()).isNull();
  }

  @Test
  void updateRejectsUnknownSource() {
    assertThatThrownBy(() -> service.update("DASHBOARD_TOP", "BANNERFLOW", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void updateRejectsUnknownSlot() {
    assertThatThrownBy(() -> service.update("SIDEBAR", "HOUSE", null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 3: 테스트를 돌려 실패를 확인**

```bash
cd /d/workspace/dpa/devpath-platform-svc && ./gradlew test --tests '*AdSlotConfigServiceTest*'
```

기대: 컴파일 실패 — `AdSlotConfigService` 심볼을 찾을 수 없다.

- [ ] **Step 4: `AdSlotSource` 작성**

`AdSlot.java`와 정확히 같은 형태로 만든다.

```java
package ai.devpath.platform.ads;

import java.util.Set;

/** 유효 슬롯 소스 문자열 카탈로그. DB CHECK(chk_ad_slot_config_source)와 일치. */
public final class AdSlotSource {
  public static final String HOUSE = "HOUSE";
  public static final String ADSENSE = "ADSENSE";
  public static final String OFF = "OFF";

  private static final Set<String> ALL = Set.of(HOUSE, ADSENSE, OFF);

  private AdSlotSource() {}

  /** 유효 소스면 그대로 반환, 아니면 IllegalArgumentException(→400). */
  public static String parse(String source) {
    if (source == null || !ALL.contains(source)) {
      throw new IllegalArgumentException("알 수 없는 광고 소스: " + source);
    }
    return source;
  }
}
```

- [ ] **Step 5: 엔티티와 리포지토리 작성**

`AdSlotConfig.java`:

```java
package ai.devpath.platform.ads;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ad_slot_config")
public class AdSlotConfig {
  @Id
  private String slot;

  private String source = AdSlotSource.HOUSE;

  @Column(name = "adsense_slot_id")
  private String adsenseSlotId;

  public String getSlot() { return slot; }
  public void setSlot(String v) { this.slot = v; }
  public String getSource() { return source; }
  public void setSource(String v) { this.source = v; }
  public String getAdsenseSlotId() { return adsenseSlotId; }
  public void setAdsenseSlotId(String v) { this.adsenseSlotId = v; }
}
```

`AdSlotConfigRepository.java`:

```java
package ai.devpath.platform.ads;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdSlotConfigRepository extends JpaRepository<AdSlotConfig, String> {
  List<AdSlotConfig> findAllByOrderBySlotAsc();
}
```

- [ ] **Step 6: 서비스 작성**

`AdSlotConfigService.java`:

```java
package ai.devpath.platform.ads;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 슬롯별 광고 소스 설정. 행이 없으면 HOUSE로 간주한다(마이그레이션 시드 이전 상태 방어). */
@Service
public class AdSlotConfigService {

  private final AdSlotConfigRepository repo;

  public AdSlotConfigService(AdSlotConfigRepository repo) {
    this.repo = repo;
  }

  @Transactional(readOnly = true)
  public AdSlotConfig get(String slot) {
    return repo.findById(AdSlot.parse(slot)).orElseGet(() -> {
      AdSlotConfig fallback = new AdSlotConfig();
      fallback.setSlot(slot);
      fallback.setSource(AdSlotSource.HOUSE);
      return fallback;
    });
  }

  @Transactional(readOnly = true)
  public List<AdSlotConfig> list() {
    return repo.findAllByOrderBySlotAsc();
  }

  /**
   * 슬롯 설정을 갱신한다.
   *
   * <p>source=ADSENSE인데 단위 ID가 비어도 저장을 허용한다 — 그 조합은 서빙에서 204로
   * 접히는 것이 정의된 동작이며, 여기서 거부하면 그 분기가 도달 불가능해진다.
   */
  @Transactional
  public AdSlotConfig update(String slot, String source, String adsenseSlotId) {
    String validSlot = AdSlot.parse(slot);
    String validSource = AdSlotSource.parse(source);

    AdSlotConfig row = repo.findById(validSlot).orElseGet(() -> {
      AdSlotConfig created = new AdSlotConfig();
      created.setSlot(validSlot);
      return created;
    });
    row.setSource(validSource);
    row.setAdsenseSlotId(normalize(adsenseSlotId));
    return repo.save(row);
  }

  /** 공백만 있는 입력은 null로 정규화한다(미설정과 같게 다루기 위해). */
  private static String normalize(String v) {
    if (v == null) {
      return null;
    }
    String trimmed = v.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
```

- [ ] **Step 7: 테스트를 돌려 통과를 확인**

```bash
cd /d/workspace/dpa/devpath-platform-svc && ./gradlew test --tests '*AdSlotConfigServiceTest*'
```

기대: 4개 테스트 PASS.

- [ ] **Step 8: 커밋**

```bash
git -C /d/workspace/dpa/devpath-platform-svc add src/main/java/ai/devpath/platform/ads/AdSlotSource.java src/main/java/ai/devpath/platform/ads/AdSlotConfig.java src/main/java/ai/devpath/platform/ads/AdSlotConfigRepository.java src/main/java/ai/devpath/platform/ads/AdSlotConfigService.java src/test/java/ai/devpath/platform/ads/AdSlotConfigServiceTest.java
git -C /d/workspace/dpa/devpath-platform-svc commit -m "feat(ads): 슬롯별 광고 소스 설정 도메인을 추가한다"
```

---

## Task 3: platform-svc — 서빙 분기

**Files:**
- Create: `src/main/java/ai/devpath/platform/ads/AdSlotContent.java`
- Modify: `src/main/java/ai/devpath/platform/ads/AdServeService.java`
- Test: `src/test/java/ai/devpath/platform/ads/AdServeServiceTest.java` (기존 파일 수정)

**Interfaces:**
- Consumes: Task 2의 `AdSlotConfigService.get(String) → AdSlotConfig`, `AdSlotSource.*`
- Produces:
  - `AdSlotContent` sealed interface — `AdSlotContent.House(AdView ad)` · `AdSlotContent.Adsense(String adsenseSlotId)`
  - `AdServeService.serve(String slot, long userId) → Optional<AdSlotContent>`
  - 생성자 시그니처: `AdServeService(AdvertisementRepository, AdSettingsService, AdSlotConfigService, Random)`

> **주의:** 기존 `AdServeServiceTest`의 3개 테스트는 모두 `new AdServeService(repo, settings, new Random(0))`를 직접 호출한다. 생성자에 인자가 하나 늘어나므로 **3개 전부 수정**해야 하며, `weightedSelectionPicksByRandom`은 반환 타입도 함께 바뀐다.

- [ ] **Step 1: 실패하는 테스트 작성 — 기존 파일 전체 교체**

`src/test/java/ai/devpath/platform/ads/AdServeServiceTest.java`를 아래로 교체한다.

```java
package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
  private final AdSlotConfigService slotConfigs = mock(AdSlotConfigService.class);

  private void slotSource(String source, String adsenseSlotId) {
    AdSlotConfig cfg = new AdSlotConfig();
    cfg.setSlot("DASHBOARD_TOP");
    cfg.setSource(source);
    cfg.setAdsenseSlotId(adsenseSlotId);
    when(slotConfigs.get(anyString())).thenReturn(cfg);
  }

  @Test
  void returnsEmptyWhenGloballyDisabled() {
    when(settings.isEnabled()).thenReturn(false);
    AdServeService svc = new AdServeService(repo, settings, slotConfigs, new Random(0));
    assertThat(svc.serve("DASHBOARD_TOP", 1L)).isEmpty();
  }

  @Test
  void returnsEmptyWhenSlotIsOff() {
    when(settings.isEnabled()).thenReturn(true);
    slotSource(AdSlotSource.OFF, null);
    AdServeService svc = new AdServeService(repo, settings, slotConfigs, new Random(0));
    assertThat(svc.serve("DASHBOARD_TOP", 1L)).isEmpty();
  }

  @Test
  void returnsEmptyWhenNoEligible() {
    when(settings.isEnabled()).thenReturn(true);
    slotSource(AdSlotSource.HOUSE, null);
    when(repo.findEligible(anyString(), any(Instant.class))).thenReturn(List.of());
    AdServeService svc = new AdServeService(repo, settings, slotConfigs, new Random(0));
    assertThat(svc.serve("DASHBOARD_TOP", 1L)).isEmpty();
  }

  @Test
  void weightedSelectionPicksByRandom() {
    when(settings.isEnabled()).thenReturn(true);
    slotSource(AdSlotSource.HOUSE, null);
    Advertisement a = ad(10L, "A", 1);
    Advertisement b = ad(20L, "B", 3); // 총 weight 4, [0,1)->A, [1,4)->B
    when(repo.findEligible(anyString(), any(Instant.class))).thenReturn(List.of(a, b));

    Random fixed = mock(Random.class);
    when(fixed.nextInt(4)).thenReturn(2); // 2 → 누적 A(1) 초과 → B
    AdServeService svc = new AdServeService(repo, settings, slotConfigs, fixed);

    Optional<AdSlotContent> content = svc.serve("DASHBOARD_TOP", 1L);
    assertThat(content).isPresent();
    assertThat(content.get()).isInstanceOf(AdSlotContent.House.class);
    assertThat(((AdSlotContent.House) content.get()).ad().id()).isEqualTo(20L);
  }

  @Test
  void returnsAdsenseWhenSlotIsAdsenseWithUnitId() {
    when(settings.isEnabled()).thenReturn(true);
    slotSource(AdSlotSource.ADSENSE, "1234567890");
    AdServeService svc = new AdServeService(repo, settings, slotConfigs, new Random(0));

    Optional<AdSlotContent> content = svc.serve("DASHBOARD_TOP", 1L);
    assertThat(content).isPresent();
    assertThat(content.get()).isInstanceOf(AdSlotContent.Adsense.class);
    assertThat(((AdSlotContent.Adsense) content.get()).adsenseSlotId()).isEqualTo("1234567890");
  }

  @Test
  void returnsEmptyWhenAdsenseHasNoUnitId() {
    when(settings.isEnabled()).thenReturn(true);
    slotSource(AdSlotSource.ADSENSE, null);
    AdServeService svc = new AdServeService(repo, settings, slotConfigs, new Random(0));
    assertThat(svc.serve("DASHBOARD_TOP", 1L)).isEmpty();
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

- [ ] **Step 2: 테스트를 돌려 실패를 확인**

```bash
cd /d/workspace/dpa/devpath-platform-svc && ./gradlew test --tests '*AdServeServiceTest*'
```

기대: 컴파일 실패 — `AdSlotContent` 심볼 없음, `AdServeService` 생성자 인자 개수 불일치.

- [ ] **Step 3: `AdSlotContent` 작성**

```java
package ai.devpath.platform.ads;

/**
 * 슬롯이 서빙할 내용. sealed로 두어 "ADSENSE인데 title이 있는" 표현 불가능한
 * 상태를 아예 만들 수 없게 한다.
 */
public sealed interface AdSlotContent {

  /** 하우스/스폰서 광고 1건. */
  record House(AdView ad) implements AdSlotContent {}

  /** 애드센스 광고 단위. 자체 노출·클릭 추적을 붙이지 않는다(구글 정책). */
  record Adsense(String adsenseSlotId) implements AdSlotContent {}
}
```

- [ ] **Step 4: `AdServeService` 수정**

`serve` 메서드와 생성자만 바꾼다. `pickWeighted`·`userShouldSeeAds`는 그대로 둔다.

```java
  private final AdvertisementRepository ads;
  private final AdSettingsService settings;
  private final AdSlotConfigService slotConfigs;
  private final Random random;

  public AdServeService(AdvertisementRepository ads, AdSettingsService settings,
      AdSlotConfigService slotConfigs, Random random) {
    this.ads = ads;
    this.settings = settings;
    this.slotConfigs = slotConfigs;
    this.random = random;
  }

  @Transactional(readOnly = true)
  public Optional<AdSlotContent> serve(String slot, long userId) {
    if (!settings.isEnabled() || !userShouldSeeAds(userId)) {
      return Optional.empty();
    }
    AdSlotConfig config = slotConfigs.get(slot);
    return switch (config.getSource()) {
      case AdSlotSource.OFF -> Optional.empty();
      case AdSlotSource.ADSENSE -> adsense(config);
      default -> house(slot);
    };
  }

  /** 단위 ID가 없으면 접는다(미설정). */
  private Optional<AdSlotContent> adsense(AdSlotConfig config) {
    String unitId = config.getAdsenseSlotId();
    return unitId == null
        ? Optional.empty()
        : Optional.of(new AdSlotContent.Adsense(unitId));
  }

  private Optional<AdSlotContent> house(String slot) {
    List<Advertisement> eligible = ads.findEligible(AdSlot.parse(slot), Instant.now());
    if (eligible.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new AdSlotContent.House(AdView.of(pickWeighted(eligible))));
  }
```

> `case AdSlotSource.OFF ->`는 Java 21의 상수 패턴이 아니라 **`String` switch의 상수 라벨**이다. `AdSlotSource.OFF`가 `static final String` 컴파일 타임 상수이므로 라벨로 쓸 수 있다.

- [ ] **Step 5: 테스트를 돌려 통과를 확인**

```bash
cd /d/workspace/dpa/devpath-platform-svc && ./gradlew test --tests '*AdServeServiceTest*'
```

기대: 6개 테스트 PASS.

- [ ] **Step 6: 커밋**

```bash
git -C /d/workspace/dpa/devpath-platform-svc add src/main/java/ai/devpath/platform/ads/AdSlotContent.java src/main/java/ai/devpath/platform/ads/AdServeService.java src/test/java/ai/devpath/platform/ads/AdServeServiceTest.java
git -C /d/workspace/dpa/devpath-platform-svc commit -m "feat(ads): 슬롯 소스에 따라 하우스/애드센스를 분기해 서빙한다"
```

---

## Task 4: platform-svc — `GET /ads` 봉투 응답

**Files:**
- Create: `src/main/java/ai/devpath/platform/ads/dto/AdServeResponse.java`
- Modify: `src/main/java/ai/devpath/platform/ads/AdController.java`
- Test: `src/test/java/ai/devpath/platform/ads/AdServeContractTest.java` (신설)

**Interfaces:**
- Consumes: Task 3의 `AdServeService.serve(String, long) → Optional<AdSlotContent>`
- Produces: `GET /ads?slot=X`의 wire 계약 — 200 `{"type":"HOUSE","ad":{id,title,imageUrl,linkUrl,slot}}` 또는 200 `{"type":"ADSENSE","adsenseSlotId":"…"}` 또는 204

> 기존 `AdControllerTest`는 `/ads/{id}/events`만 다루고 서빙 응답 형태를 단언하지 않는다. **그 파일은 건드리지 말고** 계약 테스트를 새 파일로 만든다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/ai/devpath/platform/ads/AdServeContractTest.java`:

```java
package ai.devpath.platform.ads;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.platform.auth.jwt.JwtService;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** GET /ads 판별 유니온 봉투 계약. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdServeContractTest {

  @Autowired MockMvc mvc;
  @Autowired JwtService jwt;
  @Autowired UserRepository users;
  @MockitoBean AdServeService serve;

  private String token() {
    User u = new User();
    u.setEmail("adserve-" + System.nanoTime() + "@example.com");
    u.setNickname("광고유저");
    u.setRole("LEARNER");
    u.setStatus("ACTIVE");
    u.setOnboardingStatus("PENDING");
    u = users.save(u);
    return jwt.mintAccessToken(u.getId(), "LEARNER");
  }

  @Test
  void houseAdIsWrappedInEnvelope() throws Exception {
    when(serve.serve(anyString(), anyLong())).thenReturn(Optional.of(
        new AdSlotContent.House(
            new AdView(7L, "배너", null, "https://e.com/land", "DASHBOARD_TOP"))));

    mvc.perform(get("/ads").param("slot", "DASHBOARD_TOP")
            .header("Authorization", "Bearer " + token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("HOUSE"))
        .andExpect(jsonPath("$.ad.id").value(7))
        .andExpect(jsonPath("$.ad.linkUrl").value("https://e.com/land"))
        .andExpect(jsonPath("$.adsenseSlotId").doesNotExist());
  }

  @Test
  void adsenseUnitIsWrappedInEnvelope() throws Exception {
    when(serve.serve(anyString(), anyLong()))
        .thenReturn(Optional.of(new AdSlotContent.Adsense("1234567890")));

    mvc.perform(get("/ads").param("slot", "DASHBOARD_TOP")
            .header("Authorization", "Bearer " + token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("ADSENSE"))
        .andExpect(jsonPath("$.adsenseSlotId").value("1234567890"))
        .andExpect(jsonPath("$.ad").doesNotExist());
  }

  @Test
  void emptyServeIsNoContent() throws Exception {
    when(serve.serve(anyString(), anyLong())).thenReturn(Optional.empty());

    mvc.perform(get("/ads").param("slot", "DASHBOARD_TOP")
            .header("Authorization", "Bearer " + token()))
        .andExpect(status().isNoContent());
  }
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인**

```bash
cd /d/workspace/dpa/devpath-platform-svc && ./gradlew test --tests '*AdServeContractTest*'
```

기대: `houseAdIsWrappedInEnvelope`가 실패 — 현재 응답은 봉투 없이 `{"id":7,…}`이므로 `$.type`이 없다.

- [ ] **Step 3: 응답 DTO 작성**

`src/main/java/ai/devpath/platform/ads/dto/AdServeResponse.java`:

```java
package ai.devpath.platform.ads.dto;

import ai.devpath.platform.ads.AdSlotContent;
import ai.devpath.platform.ads.AdView;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * GET /ads 응답 봉투. 도메인의 sealed AdSlotContent를 wire 표현으로 옮기는
 * 유일한 지점이며, 조립은 AdController에서만 한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdServeResponse(String type, AdView ad, String adsenseSlotId) {

  public static AdServeResponse from(AdSlotContent content) {
    return switch (content) {
      case AdSlotContent.House h -> new AdServeResponse("HOUSE", h.ad(), null);
      case AdSlotContent.Adsense a -> new AdServeResponse("ADSENSE", null, a.adsenseSlotId());
    };
  }
}
```

- [ ] **Step 4: `AdController` 수정**

`ad` 메서드만 바꾼다. import에 `ai.devpath.platform.ads.dto.AdServeResponse`를 추가한다.

```java
  /** GET /ads?slot=DASHBOARD_TOP — 서빙할 내용(200) 또는 없음(204). */
  @GetMapping("/ads")
  public ResponseEntity<AdServeResponse> ad(
      @AuthenticationPrincipal Jwt jwt, @RequestParam String slot) {
    return serve.serve(slot, Long.parseLong(jwt.getSubject()))
        .map(AdServeResponse::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }
```

- [ ] **Step 5: 테스트를 돌려 통과를 확인**

```bash
cd /d/workspace/dpa/devpath-platform-svc && ./gradlew test --tests '*AdServeContractTest*' --tests '*AdControllerTest*'
```

기대: 신규 3개 + 기존 2개 전부 PASS.

- [ ] **Step 6: 커밋**

```bash
git -C /d/workspace/dpa/devpath-platform-svc add src/main/java/ai/devpath/platform/ads/dto/AdServeResponse.java src/main/java/ai/devpath/platform/ads/AdController.java src/test/java/ai/devpath/platform/ads/AdServeContractTest.java
git -C /d/workspace/dpa/devpath-platform-svc commit -m "feat(ads): GET /ads 응답을 판별 유니온 봉투로 확장한다"
```

---

## Task 5: platform-svc — admin 슬롯 설정 API

**Files:**
- Create: `src/main/java/ai/devpath/platform/ads/dto/AdSlotConfigView.java`
- Create: `src/main/java/ai/devpath/platform/ads/dto/AdSlotConfigRequest.java`
- Modify: `src/main/java/ai/devpath/platform/ads/AdminAdController.java`
- Test: `src/test/java/ai/devpath/platform/ads/AdSlotConfigApiTest.java`

**Interfaces:**
- Consumes: Task 2의 `AdSlotConfigService`
- Produces:
  - `GET /admin/ads/slot-config` → `[{"slot":…,"source":…,"adsenseSlotId":…}]` (slot 오름차순 3행)
  - `PUT /admin/ads/slot-config/{slot}` body `{"source":…,"adsenseSlotId":…}` → 갱신된 행 1개

> 이 레포는 `IllegalArgumentException`을 shared의 예외 핸들러가 400으로 변환한다(에러 envelope 표준화). 별도 예외 처리를 추가하지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/ai/devpath/platform/ads/AdSlotConfigApiTest.java`:

```java
package ai.devpath.platform.ads;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.platform.auth.jwt.JwtService;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdSlotConfigApiTest {

  @Autowired MockMvc mvc;
  @Autowired JwtService jwt;
  @Autowired UserRepository users;
  @Autowired AdSlotConfigService service;

  private String adminToken() {
    User u = new User();
    u.setEmail("adslot-" + System.nanoTime() + "@example.com");
    u.setNickname("관리자");
    u.setRole("ADMIN");
    u.setStatus("ACTIVE");
    u.setOnboardingStatus("PENDING");
    u = users.save(u);
    return jwt.mintAccessToken(u.getId(), "ADMIN");
  }

  /** 공유 테스트 DB이므로 세 행 전부를 시드 상태로 되돌린다(실행 순서 독립). */
  @AfterEach
  void restoreSeed() {
    for (String slot : new String[] {"DASHBOARD_TOP", "COMMUNITY_FEED", "CONTENT_PAGE"}) {
      service.update(slot, "HOUSE", null);
    }
  }

  @Test
  void listReturnsThreeSlots() throws Exception {
    mvc.perform(get("/admin/ads/slot-config")
            .header("Authorization", "Bearer " + adminToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].slot").value("COMMUNITY_FEED"));
  }

  @Test
  void updateStoresSourceAndUnitId() throws Exception {
    mvc.perform(put("/admin/ads/slot-config/DASHBOARD_TOP")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"source\":\"ADSENSE\",\"adsenseSlotId\":\"1234567890\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("ADSENSE"))
        .andExpect(jsonPath("$.adsenseSlotId").value("1234567890"));
  }

  @Test
  void blankUnitIdIsStoredAsNullAndAllowed() throws Exception {
    mvc.perform(put("/admin/ads/slot-config/DASHBOARD_TOP")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"source\":\"ADSENSE\",\"adsenseSlotId\":\"   \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("ADSENSE"))
        .andExpect(jsonPath("$.adsenseSlotId").doesNotExist());
  }

  @Test
  void unknownSourceIsRejectedWith400() throws Exception {
    mvc.perform(put("/admin/ads/slot-config/DASHBOARD_TOP")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"source\":\"BANNERFLOW\",\"adsenseSlotId\":null}"))
        .andExpect(status().isBadRequest());
  }
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인**

```bash
cd /d/workspace/dpa/devpath-platform-svc && ./gradlew test --tests '*AdSlotConfigApiTest*'
```

기대: `listReturnsThreeSlots`가 404로 실패한다 — 엔드포인트가 없다.

- [ ] **Step 3: DTO 작성**

`dto/AdSlotConfigView.java`:

```java
package ai.devpath.platform.ads.dto;

import ai.devpath.platform.ads.AdSlotConfig;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdSlotConfigView(String slot, String source, String adsenseSlotId) {
  public static AdSlotConfigView of(AdSlotConfig c) {
    return new AdSlotConfigView(c.getSlot(), c.getSource(), c.getAdsenseSlotId());
  }
}
```

`dto/AdSlotConfigRequest.java`:

```java
package ai.devpath.platform.ads.dto;

public record AdSlotConfigRequest(String source, String adsenseSlotId) {}
```

- [ ] **Step 4: `AdminAdController`에 엔드포인트 추가**

생성자에 `AdSlotConfigService slotConfigService`를 추가하고 필드로 보관한 뒤, 아래 두 메서드를 추가한다. import에 `ai.devpath.platform.ads.dto.AdSlotConfigRequest`·`AdSlotConfigView`를 더한다(`dto.*`가 아니라 명시 import — 기존 파일이 개별 import 방식이다).

```java
  @GetMapping("/slot-config")
  public List<AdSlotConfigView> slotConfigs() {
    return slotConfigService.list().stream().map(AdSlotConfigView::of).toList();
  }

  @PutMapping("/slot-config/{slot}")
  public AdSlotConfigView updateSlotConfig(
      @PathVariable String slot, @RequestBody AdSlotConfigRequest body) {
    return AdSlotConfigView.of(
        slotConfigService.update(slot, body.source(), body.adsenseSlotId()));
  }
```

- [ ] **Step 5: 테스트를 돌려 통과를 확인**

```bash
cd /d/workspace/dpa/devpath-platform-svc && ./gradlew test --tests '*AdSlotConfigApiTest*'
```

기대: 4개 PASS.

- [ ] **Step 6: 전체 스위트 실행**

```bash
cd /d/workspace/dpa/devpath-platform-svc && ./gradlew test
```

기대: BUILD SUCCESSFUL. 이 레포의 전체 스위트는 **Redis가 필요**하다 — 실패가 나면 `docker compose up -d`로 인프라가 떠 있는지 먼저 확인한다.

- [ ] **Step 7: 커밋과 PR**

```bash
git -C /d/workspace/dpa/devpath-platform-svc add src/main/java/ai/devpath/platform/ads/dto/AdSlotConfigView.java src/main/java/ai/devpath/platform/ads/dto/AdSlotConfigRequest.java src/main/java/ai/devpath/platform/ads/AdminAdController.java src/test/java/ai/devpath/platform/ads/AdSlotConfigApiTest.java
git -C /d/workspace/dpa/devpath-platform-svc commit -m "feat(ads): admin 슬롯 설정 조회·수정 API를 추가한다"
git -C /d/workspace/dpa/devpath-platform-svc push -u origin feat/adsense-slot-config
gh pr create --repo DevPathAi/devpath-platform-svc --base develop --head feat/adsense-slot-config --title "feat(ads): 애드센스 슬롯 설정과 서빙 봉투" --body "슬롯별 소스(HOUSE/ADSENSE/OFF)를 admin에서 관리하고, GET /ads 응답을 판별 유니온 봉투로 확장한다. 시드가 전부 HOUSE라 배포 직후 동작은 지금과 같다."
```

- [ ] **Step 8: CI 녹색 확인 후 머지**

```bash
gh pr checks --repo DevPathAi/devpath-platform-svc feat/adsense-slot-config --watch
gh pr merge --repo DevPathAi/devpath-platform-svc feat/adsense-slot-config --merge
```

---

## Task 6: frontend — 응답 파싱 유니온

**Files:**
- Create: `apps/web/lib/src/features/ads/data/ad_slot_content.dart`
- Modify: `apps/web/lib/src/features/ads/data/ads_source.dart`
- Test: `apps/web/test/features/ads/ads_source_test.dart`

**Interfaces:**
- Consumes: Task 4의 wire 계약
- Produces:
  - `sealed class AdSlotContent` — `HouseAd(AdView ad)` · `AdsenseUnit(String adsenseSlotId)`
  - `AdSlotContent? adSlotContentFromJson(Map<String, dynamic> json)`
  - `typedef AdFetch = Future<AdSlotContent?> Function(String slot)` (기존 `Future<AdView?>`에서 변경)

> **파급 실측 결과:** 광고를 끄려고 `adFetchProvider.overrideWithValue((slot) async => null)`을 쓰는 무관 테스트 4곳(`content_page_test.dart:364` · `content_practice_action_color_test.dart:45` · `dashboard_header_test.dart:14` · `page_header_scroll_test.dart:199`)은 `Null`이 모든 nullable 타입의 서브타입이라 **수정할 필요가 없다.** 건드리지 않는다.

- [ ] **Step 1: 브랜치 분기**

```bash
git -C /d/workspace/dpa/devpath-frontend fetch origin
git -C /d/workspace/dpa/devpath-frontend switch -c feat/adsense-integration origin/develop
```

- [ ] **Step 2: 실패하는 테스트 작성**

`apps/web/test/features/ads/ads_source_test.dart`를 아래로 교체한다.

```dart
import 'package:devpath_web/src/features/ads/data/ad_slot_content.dart';
import 'package:devpath_web/src/features/ads/data/ad_view.dart';
import 'package:devpath_web/src/features/ads/data/ads_source.dart';
import 'package:devpath_web/src/providers/api_providers.dart';
import 'package:dp_core/dp_core.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _ThrowingClient implements ApiClient {
  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw const ApiException(code: ApiErrorCode.unknown, message: 'boom');
}

void main() {
  test('AdView.fromJson parses fields', () {
    final a = AdView.fromJson({
      'id': 3,
      'title': '광고',
      'imageUrl': null,
      'linkUrl': 'https://e.com',
      'slot': 'DASHBOARD_TOP',
    });
    expect(a.id, 3);
    expect(a.linkUrl, 'https://e.com');
    expect(a.imageUrl, isNull);
  });

  test('type=HOUSE parses into HouseAd', () {
    final c = adSlotContentFromJson({
      'type': 'HOUSE',
      'ad': {
        'id': 3,
        'title': '광고',
        'imageUrl': null,
        'linkUrl': 'https://e.com',
        'slot': 'DASHBOARD_TOP',
      },
    });
    expect(c, isA<HouseAd>());
    expect((c! as HouseAd).ad.id, 3);
  });

  test('type=ADSENSE parses into AdsenseUnit', () {
    final c = adSlotContentFromJson({
      'type': 'ADSENSE',
      'adsenseSlotId': '1234567890',
    });
    expect(c, isA<AdsenseUnit>());
    expect((c! as AdsenseUnit).adsenseSlotId, '1234567890');
  });

  test('unknown type returns null (forward compatible)', () {
    expect(adSlotContentFromJson({'type': 'TAKEOVER'}), isNull);
  });

  test('HOUSE without ad payload returns null', () {
    expect(adSlotContentFromJson({'type': 'HOUSE'}), isNull);
  });

  test('ADSENSE without unit id returns null', () {
    expect(adSlotContentFromJson({'type': 'ADSENSE'}), isNull);
  });

  test('adFetchProvider returns null on ApiException (fail-silent)', () async {
    final c = ProviderContainer(
      overrides: [apiClientProvider.overrideWithValue(_ThrowingClient())],
    );
    addTearDown(c.dispose);
    final result = await c.read(adFetchProvider)('DASHBOARD_TOP');
    expect(result, isNull);
  });
}
```

- [ ] **Step 3: 테스트를 돌려 실패를 확인**

```bash
cd /d/workspace/dpa/devpath-frontend/apps/web && flutter test test/features/ads/ads_source_test.dart
```

기대: 컴파일 실패 — `ad_slot_content.dart`가 없다.

- [ ] **Step 4: 유니온과 파싱 작성**

`apps/web/lib/src/features/ads/data/ad_slot_content.dart`:

```dart
import 'ad_view.dart';

/// 슬롯이 그릴 내용. 백엔드 GET /ads 봉투(`{"type":…}`)에 대응한다.
sealed class AdSlotContent {
  const AdSlotContent();
}

/// 하우스/스폰서 광고 1건. 노출·클릭 측정 대상이다.
class HouseAd extends AdSlotContent {
  const HouseAd(this.ad);
  final AdView ad;
}

/// 애드센스 광고 단위. **자체 측정을 붙이지 않는다**(구글 정책).
class AdsenseUnit extends AdSlotContent {
  const AdsenseUnit(this.adsenseSlotId);
  final String adsenseSlotId;
}

/// 봉투를 파싱한다. 모르는 `type`이나 필수 필드 누락은 null(전방 호환 + fail-silent).
AdSlotContent? adSlotContentFromJson(Map<String, dynamic> json) {
  switch (json['type']) {
    case 'HOUSE':
      final ad = json['ad'];
      if (ad is! Map) return null;
      try {
        return HouseAd(AdView.fromJson(ad.cast<String, dynamic>()));
      } catch (_) {
        return null;
      }
    case 'ADSENSE':
      final id = json['adsenseSlotId'];
      if (id is! String || id.isEmpty) return null;
      return AdsenseUnit(id);
    default:
      return null;
  }
}
```

- [ ] **Step 5: `ads_source.dart` 수정**

`adFetchProvider`만 바꾼다. `adEventProvider`는 그대로 둔다.

```dart
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../providers/api_providers.dart';
import 'ad_slot_content.dart';

typedef AdFetch = Future<AdSlotContent?> Function(String slot);
typedef AdEvent = Future<void> Function(int id, String type);

/// GET /ads?slot= — 200이면 봉투 파싱, 204/에러/미지의 type이면 null(fail-silent).
final adFetchProvider = Provider<AdFetch>((ref) {
  final client = ref.watch(apiClientProvider);
  return (slot) async {
    try {
      final json = await client.get<Map<String, dynamic>?>(
        '/ads',
        query: {'slot': slot},
      );
      if (json == null || json.isEmpty) return null; // 204 → 빈 본문
      return adSlotContentFromJson(json);
    } catch (_) {
      return null; // fail-silent
    }
  };
});
```

> `ad_view.dart` import가 더 이상 필요 없으므로 제거한다. `analyze`가 unused import를 잡는다.

- [ ] **Step 6: 테스트를 돌려 통과를 확인**

```bash
cd /d/workspace/dpa/devpath-frontend/apps/web && flutter test test/features/ads/ads_source_test.dart
```

기대: 7개 PASS. (`ad_slot_widget_test.dart`는 이 시점에 컴파일이 깨진다 — Task 8에서 고친다.)

- [ ] **Step 7: 커밋**

```bash
git -C /d/workspace/dpa/devpath-frontend add apps/web/lib/src/features/ads/data/ad_slot_content.dart apps/web/lib/src/features/ads/data/ads_source.dart apps/web/test/features/ads/ads_source_test.dart
git -C /d/workspace/dpa/devpath-frontend commit -m "feat(ads): /ads 봉투 응답을 sealed 유니온으로 파싱한다"
```

---

## Task 7: frontend — 애드센스 뷰 (조건부 import 3분할)

**Files:**
- Create: `apps/web/lib/src/features/ads/presentation/adsense_unit_view.dart`
- Create: `apps/web/lib/src/features/ads/presentation/adsense_unit_view_stub.dart`
- Create: `apps/web/lib/src/features/ads/presentation/adsense_unit_view_web.dart`
- Test: `apps/web/test/features/ads/adsense_unit_view_test.dart`

**Interfaces:**
- Consumes: 없음 (독립 위젯)
- Produces:
  - `class AdSenseUnitView extends StatefulWidget` — `const AdSenseUnitView({super.key, required this.slotId})`
  - `abstract class AdSenseHandle { Widget get view; void dispose(); }`
  - `AdSenseHandle createAdSenseHandle({required String slotId, required void Function(String status, double height) onResolved})` — stub과 web 양쪽이 같은 시그니처

> **이 Task는 index.html을 건드리지 않는다.** 스크립트 배선은 Task 8에서 한다. 여기서는 Dart 쪽 구조와 stub 동작만 만든다.

- [ ] **Step 1: 실패하는 테스트 작성**

`apps/web/test/features/ads/adsense_unit_view_test.dart`:

```dart
import 'package:devpath_web/src/features/ads/presentation/adsense_unit_view.dart';
import 'package:dp_design/dp_design.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('VM(stub)에서는 아무것도 그리지 않는다', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: DpTheme.light(),
        home: const Scaffold(body: AdSenseUnitView(slotId: '1234567890')),
      ),
    );
    await tester.pumpAndSettle();

    final size = tester.getSize(find.byType(AdSenseUnitView));
    expect(size.height, 0);
  });
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인**

```bash
cd /d/workspace/dpa/devpath-frontend/apps/web && flutter test test/features/ads/adsense_unit_view_test.dart
```

기대: 컴파일 실패 — `adsense_unit_view.dart`가 없다.

- [ ] **Step 3: 추상 위젯 작성**

`adsense_unit_view.dart`:

```dart
import 'package:flutter/material.dart';

import 'adsense_unit_view_stub.dart'
    if (dart.library.js_interop) 'adsense_unit_view_web.dart'
    as impl;

/// web 구현이 반환하는 핸들. stub도 동일 인터페이스를 만족한다.
abstract class AdSenseHandle {
  Widget get view;
  void dispose();
}

/// 애드센스 광고 단위. web=`<ins class="adsbygoogle">` 임베드, 그 외(테스트 포함)=stub.
///
/// 높이 0으로 시작해, 광고가 채워졌다는 신호를 받은 뒤에만 확장한다.
/// 채워지지 않으면(심사 대기·미매칭·애드블록·스크립트 차단·타임아웃) 접은 채로 둔다.
///
/// viewType은 **State에서 1회**(initState) 생성한다. 함수형 build에서 매 rebuild마다
/// 만들면 viewFactory가 무한 증식한다(Monaco에 기록된 함정).
class AdSenseUnitView extends StatefulWidget {
  const AdSenseUnitView({super.key, required this.slotId});
  final String slotId;

  @override
  State<AdSenseUnitView> createState() => _AdSenseUnitViewState();
}

class _AdSenseUnitViewState extends State<AdSenseUnitView> {
  late final AdSenseHandle _handle;
  double _height = 0;

  @override
  void initState() {
    super.initState();
    _handle = impl.createAdSenseHandle(
      slotId: widget.slotId,
      onResolved: (status, height) {
        if (!mounted) return;
        setState(() => _height = status == 'filled' ? height : 0);
      },
    );
  }

  @override
  void dispose() {
    _handle.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(height: _height, child: _handle.view);
  }
}
```

- [ ] **Step 4: stub 작성**

`adsense_unit_view_stub.dart`:

```dart
import 'package:flutter/widgets.dart';

import 'adsense_unit_view.dart' show AdSenseHandle;

/// 비웹/테스트: 애드센스를 로드하지 않는다. 높이 0 그대로 남으므로 화면에 아무것도 없다.
AdSenseHandle createAdSenseHandle({
  required String slotId,
  required void Function(String status, double height) onResolved,
}) => _StubHandle();

class _StubHandle implements AdSenseHandle {
  @override
  Widget get view => const SizedBox.shrink();

  @override
  void dispose() {}
}
```

- [ ] **Step 5: web 구현 작성**

`adsense_unit_view_web.dart`:

```dart
import 'dart:js_interop';
import 'dart:ui_web' as ui_web;

import 'package:flutter/widgets.dart';
import 'package:web/web.dart' as web;

import 'adsense_unit_view.dart' show AdSenseHandle;

/// index.html이 정의하는 심: createDevpathAdUnit(container, slotId, onResolved)
/// → { dispose } 반환. onResolved(status, height)는 정확히 1회 호출된다.
extension type _JsAdHandle._(JSObject _) implements JSObject {
  external void dispose();
}

@JS('createDevpathAdUnit')
external _JsAdHandle _createDevpathAdUnit(
  web.HTMLElement container,
  String slotId,
  JSFunction onResolved,
);

int _seq = 0;

/// viewType은 인스턴스마다 새로 만든다. 같은 `<ins>`를 재사용하면 구글이
/// "All ins elements ... already have ads in them"으로 거부한다.
AdSenseHandle createAdSenseHandle({
  required String slotId,
  required void Function(String status, double height) onResolved,
}) {
  final viewType = 'adsense-${_seq++}';
  _JsAdHandle? jsHandle;

  ui_web.platformViewRegistry.registerViewFactory(viewType, (int _) {
    final container = (web.document.createElement('div') as web.HTMLDivElement)
      ..style.width = '100%'
      // 확장 전에는 0높이 박스 밖으로 광고가 삐져나오지 않게 잘라둔다.
      ..style.overflow = 'hidden';
    final cb = ((JSString status, JSNumber height) =>
        onResolved(status.toDart, height.toDartDouble)).toJS;
    jsHandle = _createDevpathAdUnit(container, slotId, cb);
    return container;
  });

  return _WebHandle(viewType, () => jsHandle);
}

class _WebHandle implements AdSenseHandle {
  _WebHandle(this._viewType, this._jsHandle);
  final String _viewType;
  final _JsAdHandle? Function() _jsHandle;

  @override
  Widget get view => HtmlElementView(viewType: _viewType);

  @override
  void dispose() => _jsHandle()?.dispose();
}
```

- [ ] **Step 6: 테스트를 돌려 통과를 확인**

```bash
cd /d/workspace/dpa/devpath-frontend/apps/web && flutter test test/features/ads/adsense_unit_view_test.dart
```

기대: PASS.

- [ ] **Step 7: 커밋**

```bash
git -C /d/workspace/dpa/devpath-frontend add apps/web/lib/src/features/ads/presentation/adsense_unit_view.dart apps/web/lib/src/features/ads/presentation/adsense_unit_view_stub.dart apps/web/lib/src/features/ads/presentation/adsense_unit_view_web.dart apps/web/test/features/ads/adsense_unit_view_test.dart
git -C /d/workspace/dpa/devpath-frontend commit -m "feat(ads): 애드센스 광고 단위 뷰(조건부 import 3분할)를 추가한다"
```

---

## Task 8: frontend — index.html 심과 `ads.txt`

**Files:**
- Modify: `apps/web/web/index.html`
- Create: `apps/web/web/ads.txt`
- Create: `devpath-home-page/ads.txt` (루트 도메인 `leva.ai.kr`용 — Step 3 참조)
- Modify: `devpath-home-page/build.mjs` — `DEPLOY_ENTRIES`에 `'ads.txt'` 추가

**Interfaces:**
- Consumes: Task 7의 `_createDevpathAdUnit(container, slotId, onResolved)` 호출 규약
- Produces: `window.createDevpathAdUnit(container, slotId, onResolved) → { dispose }`. `onResolved(status, height)`는 `'filled'` 또는 `'unfilled'`와 픽셀 높이를 **정확히 1회** 전달한다.

> **🔴 착수 게이트:** 퍼블리셔 ID(`ca-pub-` + 숫자 16자리)가 필요하다. 아래 코드의 `ca-pub-XXXXXXXXXXXXXXXX`와 `pub-XXXXXXXXXXXXXXXX`를 **실제 값으로 치환**한다. 값이 없으면 이 Task를 시작하지 말고 사용자에게 요청한다.

- [ ] **Step 1: 애드센스 스크립트를 `<head>`에 추가**

`apps/web/web/index.html`의 `<link rel="manifest" href="manifest.json">` 바로 다음 줄에 넣는다. **`<head>`에 정적으로 두는 이유는 심사 중 구글 크롤러가 페이지에서 스크립트를 찾아야 하기 때문이다** — 지연 주입하면 안 된다.

```html
  <!-- 구글 애드센스. 퍼블리셔 ID는 공개 값이며 심사 크롤러가 <head>에서 찾아야 한다. -->
  <script async src="https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-XXXXXXXXXXXXXXXX"
          crossorigin="anonymous"></script>
```

- [ ] **Step 2: `createDevpathAdUnit` 심을 `<body>` 스크립트 블록에 추가**

기존 `window.createDevpathEditor = …` 정의 **아래**, 같은 `<script>` 블록 안에 넣는다.

```js
    // 애드센스 광고 단위 1개를 컨테이너에 삽입한다.
    // onResolved(status, height)는 정확히 1회만 호출된다('filled' | 'unfilled').
    // 자체 노출·클릭 추적은 붙이지 않는다(구글 정책).
    window.createDevpathAdUnit = function (container, slotId, onResolved) {
      var settled = false;
      var observer = null;
      var timer = null;
      var rafId = null;

      function settle(status, height) {
        if (settled) return;
        settled = true;
        if (observer) { observer.disconnect(); observer = null; }
        if (timer) { clearTimeout(timer); timer = null; }
        if (rafId) { cancelAnimationFrame(rafId); rafId = null; }
        onResolved(status, height);
      }

      var ins = document.createElement('ins');
      ins.className = 'adsbygoogle';
      ins.style.display = 'block';
      ins.style.width = '100%';
      ins.setAttribute('data-ad-client', 'ca-pub-XXXXXXXXXXXXXXXX');
      ins.setAttribute('data-ad-slot', slotId);
      ins.setAttribute('data-ad-format', 'auto');
      ins.setAttribute('data-full-width-responsive', 'true');
      container.appendChild(ins);

      // 채워짐 여부는 구글이 data-ad-status로 알려준다. 폴링이 아니라 관측한다.
      observer = new MutationObserver(function () {
        var status = ins.getAttribute('data-ad-status');
        if (status === 'filled') {
          settle('filled', ins.offsetHeight);
        } else if (status === 'unfilled') {
          settle('unfilled', 0);
        }
      });
      observer.observe(ins, { attributes: true, attributeFilter: ['data-ad-status'] });

      // 스크립트 미로드·애드블록·응답 없음을 모두 흡수하는 단일 마감선.
      // adsbygoogle 배열이 없다고 즉시 접으면 안 된다 — 스크립트가 async라
      // 로드 전에는 없는 것이 정상이고, `|| []` 큐가 바로 그 대기용이다.
      timer = setTimeout(function () { settle('unfilled', 0); }, 8000);

      // 폭 0인 채로 push하면 "No slot size for availableWidth=0"으로 거부된다.
      // 컨테이너가 실제로 레이아웃돼 폭을 가질 때까지 프레임을 기다린 뒤 push한다.
      function pushWhenMeasurable() {
        if (settled) return;
        if (container.offsetWidth > 0) {
          try {
            (window.adsbygoogle = window.adsbygoogle || []).push({});
          } catch (e) {
            settle('unfilled', 0);
          }
          return;
        }
        rafId = requestAnimationFrame(pushWhenMeasurable);
      }
      rafId = requestAnimationFrame(pushWhenMeasurable);

      return {
        dispose: function () {
          if (observer) { observer.disconnect(); observer = null; }
          if (timer) { clearTimeout(timer); timer = null; }
          if (rafId) { cancelAnimationFrame(rafId); rafId = null; }
          if (ins.parentNode) { ins.parentNode.removeChild(ins); }
        },
      };
    };
```

- [ ] **Step 3: `ads.txt` 생성 — 앱 서브도메인과 루트 도메인 양쪽**

> **🔴 스펙 §2.4 정정.** 스펙은 "Flutter 빌드가 산출물 루트로 복사하므로 홈페이지 레포는 건드리지 않는다"고 했으나 **틀렸다.** gitops ingress 실측 결과 웹앱은 **`app.leva.ai.kr`**(`devpath-gitops/apps/devpath-web/base/ingress.yaml`)이고 루트 `leva.ai.kr`은 별도 레포 `devpath-home-page`(CF Pages)다. `ads.txt`는 페이지 호스트의 **루트 도메인**에서 조회되는 것이 표준이라 `app.leva.ai.kr/ads.txt`만 두면 찾지 못한다.

**(a) 웹앱 쪽** — `apps/web/web/ads.txt`:

```
google.com, pub-XXXXXXXXXXXXXXXX, DIRECT, f08c47fec0942fa0
```

**(b) 루트 도메인 쪽** — `devpath-home-page` 레포에 같은 내용의 `ads.txt`를 레포 루트에 만든다.

**그리고 `build.mjs`의 `DEPLOY_ENTRIES` 배열에 `'ads.txt'`를 추가한다.** 이 레포는 화이트리스트 복사 빌드라(`build.mjs:11`) 목록에 없으면 `dist/`로 나가지 않고, 파일을 만들어도 배포되지 않는다.

```js
const DEPLOY_ENTRIES = ['index.html', 'src', 'assets', '_headers', '_redirects', 'robots.txt', 'favicon.ico', 'ads.txt'];
```

이 변경은 `devpath-home-page`에서 별도 브랜치·PR로 처리한다(`develop` 분기 → PR → 머지).

> 최종 확인은 애드센스 콘솔이 요구하는 위치를 따른다. 콘솔이 `app.leva.ai.kr`만 지목하면 (b)는 불필요하지만, **없어서 못 찾는 것보다 양쪽에 두는 편이 안전하다**(중복은 무해).

- [ ] **Step 4: 치환 누락 확인**

```bash
grep -rn "XXXXXXXXXXXXXXXX" /d/workspace/dpa/devpath-frontend/apps/web/web/
```

기대: **출력 없음**. 하나라도 남아 있으면 스크립트가 404가 되고 광고가 절대 채워지지 않는다.

- [ ] **Step 5: 빌드가 깨지지 않는지 확인**

```bash
cd /d/workspace/dpa/devpath-frontend/apps/web && flutter build web --release
```

기대: 빌드 성공. `build/web/ads.txt`가 존재하는지 확인한다.

```bash
ls -l /d/workspace/dpa/devpath-frontend/apps/web/build/web/ads.txt
```

- [ ] **Step 6: 커밋**

```bash
git -C /d/workspace/dpa/devpath-frontend add apps/web/web/index.html apps/web/web/ads.txt
git -C /d/workspace/dpa/devpath-frontend commit -m "feat(ads): 애드센스 스크립트와 광고 단위 심을 배선한다"
```

---

## Task 9: frontend — `AdSlotWidget` 분기와 정책 가드

**Files:**
- Modify: `apps/web/lib/src/features/ads/presentation/ad_slot_widget.dart`
- Test: `apps/web/test/features/ads/ad_slot_widget_test.dart`

**Interfaces:**
- Consumes: Task 6의 `AdSlotContent`/`HouseAd`/`AdsenseUnit`, Task 7의 `AdSenseUnitView`
- Produces: 없음 (최종 소비 지점)

- [ ] **Step 1: 실패하는 테스트 작성 — 기존 파일 교체**

`apps/web/test/features/ads/ad_slot_widget_test.dart`를 아래로 교체한다. `_ad()`가 `HouseAd`를 돌려주도록 바뀌고, **애드센스 가지에서 측정이 일어나지 않는지 검증하는 테스트가 추가된다.**

```dart
import 'package:devpath_web/src/features/ads/application/ad_link_opener.dart';
import 'package:devpath_web/src/features/ads/data/ad_slot_content.dart';
import 'package:devpath_web/src/features/ads/data/ad_view.dart';
import 'package:devpath_web/src/features/ads/data/ads_source.dart';
import 'package:devpath_web/src/features/ads/presentation/ad_slot_widget.dart';
import 'package:devpath_web/src/features/ads/presentation/adsense_unit_view.dart';
import 'package:dp_design/dp_design.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:visibility_detector/visibility_detector.dart';

class _FakeOpener implements AdLinkOpener {
  String? opened;
  @override
  void open(String url) => opened = url;
}

HouseAd _house() => const HouseAd(
  AdView(
    id: 5,
    title: '테스트 광고',
    imageUrl: null,
    linkUrl: 'https://e.com/land',
    slot: 'DASHBOARD_TOP',
  ),
);

void main() {
  setUp(() {
    VisibilityDetectorController.instance.updateInterval = Duration.zero;
  });

  testWidgets('fetch→null renders nothing (fail-silent)', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [adFetchProvider.overrideWithValue((slot) async => null)],
        child: MaterialApp(
          theme: DpTheme.light(),
          home: const Scaffold(body: AdSlotWidget(slot: 'DASHBOARD_TOP')),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byType(InkWell), findsNothing);
    expect(find.text('광고'), findsNothing);
  });

  testWidgets('fetch→HouseAd renders title and 광고 label', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [adFetchProvider.overrideWithValue((slot) async => _house())],
        child: MaterialApp(
          theme: DpTheme.light(),
          home: const Scaffold(body: AdSlotWidget(slot: 'DASHBOARD_TOP')),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('테스트 광고'), findsOneWidget);
    expect(find.text('광고'), findsOneWidget);
    // VisibilityDetector가 남긴 타이머를 배출(pending timer assertion 방지).
    await tester.pump(const Duration(seconds: 1));
  });

  testWidgets('tap fires CLICK and opens link', (tester) async {
    final events = <String>[];
    final opener = _FakeOpener();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          adFetchProvider.overrideWithValue((slot) async => _house()),
          adEventProvider.overrideWithValue((id, type) async {
            events.add('$id:$type');
          }),
          adLinkOpenerProvider.overrideWithValue(opener),
        ],
        child: MaterialApp(
          theme: DpTheme.light(),
          home: const Scaffold(body: AdSlotWidget(slot: 'DASHBOARD_TOP')),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byType(InkWell));
    await tester.pumpAndSettle();
    expect(opener.opened, 'https://e.com/land');
    expect(events, contains('5:CLICK'));
  });

  testWidgets('fetch→AdsenseUnit renders AdSenseUnitView', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          adFetchProvider.overrideWithValue(
            (slot) async => const AdsenseUnit('1234567890'),
          ),
        ],
        child: MaterialApp(
          theme: DpTheme.light(),
          home: const Scaffold(body: AdSlotWidget(slot: 'DASHBOARD_TOP')),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final view = tester.widget<AdSenseUnitView>(find.byType(AdSenseUnitView));
    expect(view.slotId, '1234567890');
    // 하우스 광고 카드는 그려지지 않는다.
    expect(find.byType(InkWell), findsNothing);
    expect(find.text('광고'), findsNothing);
  });

  testWidgets('애드센스 가지는 노출·클릭 이벤트를 전혀 보내지 않는다 (구글 정책)', (
    tester,
  ) async {
    final events = <String>[];
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          adFetchProvider.overrideWithValue(
            (slot) async => const AdsenseUnit('1234567890'),
          ),
          adEventProvider.overrideWithValue((id, type) async {
            events.add('$id:$type');
          }),
        ],
        child: MaterialApp(
          theme: DpTheme.light(),
          home: const Scaffold(body: AdSlotWidget(slot: 'DASHBOARD_TOP')),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.pump(const Duration(seconds: 2));

    expect(events, isEmpty);
    expect(find.byType(VisibilityDetector), findsNothing);
  });
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인**

```bash
cd /d/workspace/dpa/devpath-frontend/apps/web && flutter test test/features/ads/ad_slot_widget_test.dart
```

기대: 컴파일 실패 — `_ad` 타입 불일치와 `AdSenseUnitView` 미사용.

- [ ] **Step 3: `AdSlotWidget` 수정**

`_ad` 필드 타입과 `build`·`_onVisible`·`_onTap`을 바꾼다. import에 `../data/ad_slot_content.dart`와 `adsense_unit_view.dart`를 추가하고, `../data/ad_view.dart`는 그대로 둔다(카드가 `AdView` 필드를 읽는다).

```dart
class _AdSlotWidgetState extends ConsumerState<AdSlotWidget> {
  AdSlotContent? _content;
  bool _impressed = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _fetch());
  }

  Future<void> _fetch() async {
    final content = await ref.read(adFetchProvider)(widget.slot);
    if (!mounted) return;
    setState(() => _content = content);
  }

  void _onVisible(AdView ad, double fraction) {
    if (_impressed) return;
    if (fraction >= 0.5) {
      _impressed = true;
      ref.read(adEventProvider)(ad.id, 'IMPRESSION');
    }
  }

  void _onTap(AdView ad) {
    ref.read(adEventProvider)(ad.id, 'CLICK');
    ref.read(adLinkOpenerProvider).open(ad.linkUrl);
  }

  @override
  Widget build(BuildContext context) {
    return switch (_content) {
      null => const SizedBox.shrink(),
      // 애드센스에는 측정을 붙이지 않는다(구글 정책). VisibilityDetector도 없다.
      AdsenseUnit(:final adsenseSlotId) => AdSenseUnitView(slotId: adsenseSlotId),
      HouseAd(:final ad) => _houseCard(context, ad),
    };
  }

  Widget _houseCard(BuildContext context, AdView ad) {
    final c = context.dpColors;
    final text = Theme.of(context).textTheme;

    return VisibilityDetector(
      key: Key('ad-${widget.slot}-${ad.id}'),
      onVisibilityChanged: (info) => _onVisible(ad, info.visibleFraction),
      child: Card(
        margin: const EdgeInsets.symmetric(vertical: DpSpacing.sm),
        child: InkWell(
          onTap: () => _onTap(ad),
          child: Padding(
            padding: const EdgeInsets.all(DpSpacing.md),
            child: Row(
              children: [
                if (ad.imageUrl != null)
                  Padding(
                    padding: const EdgeInsets.only(right: DpSpacing.md),
                    child: Image.network(
                      ad.imageUrl!,
                      width: 64,
                      height: 64,
                      fit: BoxFit.cover,
                      errorBuilder: (_, _, _) =>
                          const SizedBox(width: 64, height: 64),
                    ),
                  ),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '광고',
                        style: text.labelSmall?.copyWith(
                          color: c.textSecondary,
                        ),
                      ),
                      const SizedBox(height: DpSpacing.xs),
                      Text(ad.title, style: text.bodyMedium),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
```

- [ ] **Step 4: 테스트를 돌려 통과를 확인**

```bash
cd /d/workspace/dpa/devpath-frontend/apps/web && flutter test test/features/ads/
```

기대: 5개 위젯 테스트 + 7개 소스 테스트 + 1개 뷰 테스트 전부 PASS.

- [ ] **Step 5: web 앱 전체 테스트**

```bash
cd /d/workspace/dpa/devpath-frontend/apps/web && flutter test
```

기대: 전부 PASS. 특히 `adFetchProvider`를 override하는 무관 테스트 4곳이 **수정 없이 통과**하는지 확인한다.

- [ ] **Step 6: 커밋**

```bash
git -C /d/workspace/dpa/devpath-frontend add apps/web/lib/src/features/ads/presentation/ad_slot_widget.dart apps/web/test/features/ads/ad_slot_widget_test.dart
git -C /d/workspace/dpa/devpath-frontend commit -m "feat(ads): 슬롯 위젯을 하우스/애드센스/없음 세 갈래로 분기한다"
```

---

## Task 10: frontend — admin 슬롯 설정 UI

**Files:**
- Create: `apps/admin/lib/src/features/ads/data/ad_slot_config_row.dart`
- Modify: `apps/admin/lib/src/features/ads/data/ads_source.dart`
- Modify: `apps/admin/lib/src/features/ads/state/ads_state.dart`
- Modify: `apps/admin/lib/src/features/ads/application/ads_controller.dart`
- Modify: `apps/admin/lib/src/features/ads/presentation/ads_page.dart`
- Test: `apps/admin/test/features/ads/ads_page_test.dart`

**Interfaces:**
- Consumes: Task 5의 `GET /admin/ads/slot-config` · `PUT /admin/ads/slot-config/{slot}`
- Produces:
  - `class AdSlotConfigRow` — `slot` · `source` · `adsenseSlotId`, `fromJson`, `toRequestJson`, `copyWith`
  - `adSlotConfigListProvider` (`Future<List<AdSlotConfigRow>> Function()`)
  - `adSlotConfigSaveProvider` (`Future<AdSlotConfigRow> Function(AdSlotConfigRow)`)
  - `AdsState.slotConfigs` (`List<AdSlotConfigRow>`, 기본 `const []`)
  - `AdsController.saveSlotConfig(AdSlotConfigRow row)`

- [ ] **Step 1: 실패하는 테스트 작성 — 기존 파일에 추가**

`apps/admin/test/features/ads/ads_page_test.dart`의 `main()` 안 **끝에** 아래 두 테스트를 추가한다. 파일 상단 import에 `package:devpath_admin/src/features/ads/data/ad_slot_config_row.dart`를 더한다.

기존 5개 테스트에도 `adSlotConfigListProvider` override가 필요하다 — `AdsController.load()`가 슬롯 설정을 함께 읽기 때문이다. **기존 5개 테스트의 `overrides` 리스트 각각에 아래 한 줄을 추가한다.**

```dart
          adSlotConfigListProvider.overrideWithValue(() async => _configs()),
```

그리고 헬퍼를 파일 상단(`_ad` 헬퍼 아래)에 추가한다.

```dart
List<AdSlotConfigRow> _configs() => const [
  AdSlotConfigRow(
    slot: 'COMMUNITY_FEED',
    source: 'HOUSE',
    adsenseSlotId: null,
  ),
  AdSlotConfigRow(
    slot: 'CONTENT_PAGE',
    source: 'HOUSE',
    adsenseSlotId: null,
  ),
  AdSlotConfigRow(
    slot: 'DASHBOARD_TOP',
    source: 'HOUSE',
    adsenseSlotId: null,
  ),
];
```

추가할 두 테스트:

```dart
  testWidgets('슬롯 설정 버튼이 다이얼로그를 열고 3행을 보여준다', (tester) async {
    tester.view.physicalSize = const Size(1400, 1000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          adsListProvider.overrideWithValue(
            ({slot, status}) async => [_ad(1, '첫 배너')],
          ),
          adSettingsGetProvider.overrideWithValue(() async => true),
          adSlotConfigListProvider.overrideWithValue(() async => _configs()),
        ],
        child: MaterialApp(theme: DpTheme.light(), home: const AdminAdsPage()),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(OutlinedButton, '슬롯 설정'));
    await tester.pumpAndSettle();

    expect(find.text('DASHBOARD_TOP'), findsWidgets);
    expect(find.byType(DropdownButtonFormField<String>), findsNWidgets(3));
  });

  testWidgets('애드센스를 고르고 단위 ID를 비우면 경고가 뜬다', (tester) async {
    tester.view.physicalSize = const Size(1400, 1200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          adsListProvider.overrideWithValue(
            ({slot, status}) async => [_ad(1, '첫 배너')],
          ),
          adSettingsGetProvider.overrideWithValue(() async => true),
          adSlotConfigListProvider.overrideWithValue(() async => _configs()),
        ],
        child: MaterialApp(theme: DpTheme.light(), home: const AdminAdsPage()),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(OutlinedButton, '슬롯 설정'));
    await tester.pumpAndSettle();

    expect(find.text('단위 ID가 없으면 이 슬롯은 노출되지 않습니다'), findsNothing);

    await tester.tap(find.byType(DropdownButtonFormField<String>).first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('애드센스').last);
    await tester.pumpAndSettle();

    expect(find.text('단위 ID가 없으면 이 슬롯은 노출되지 않습니다'), findsOneWidget);
  });
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인**

```bash
cd /d/workspace/dpa/devpath-frontend/apps/admin && flutter test test/features/ads/ads_page_test.dart
```

기대: 컴파일 실패 — `ad_slot_config_row.dart`와 `adSlotConfigListProvider`가 없다.

- [ ] **Step 3: 모델 작성**

`apps/admin/lib/src/features/ads/data/ad_slot_config_row.dart`:

```dart
/// admin 슬롯 설정 모델. 백엔드 AdSlotConfigView(응답)·AdSlotConfigRequest(요청) 양쪽.
class AdSlotConfigRow {
  const AdSlotConfigRow({
    required this.slot,
    required this.source,
    required this.adsenseSlotId,
  });

  final String slot; // DASHBOARD_TOP | COMMUNITY_FEED | CONTENT_PAGE
  final String source; // HOUSE | ADSENSE | OFF
  final String? adsenseSlotId;

  factory AdSlotConfigRow.fromJson(Map<String, dynamic> json) => AdSlotConfigRow(
    slot: json['slot'] as String,
    source: json['source'] as String,
    adsenseSlotId: json['adsenseSlotId'] as String?,
  );

  /// PUT 바디. slot은 경로로 전달되므로 제외.
  Map<String, dynamic> toRequestJson() => {
    'source': source,
    'adsenseSlotId': adsenseSlotId,
  };

  AdSlotConfigRow copyWith({String? source, Object? adsenseSlotId = _sentinel}) =>
      AdSlotConfigRow(
        slot: slot,
        source: source ?? this.source,
        adsenseSlotId: adsenseSlotId == _sentinel
            ? this.adsenseSlotId
            : adsenseSlotId as String?,
      );

  static const _sentinel = Object();
}
```

- [ ] **Step 4: 데이터 소스 추가**

`apps/admin/lib/src/features/ads/data/ads_source.dart` 끝에 추가한다. 상단에 `import 'ad_slot_config_row.dart';`를 더한다.

```dart
typedef AdSlotConfigList = Future<List<AdSlotConfigRow>> Function();
typedef AdSlotConfigSave =
    Future<AdSlotConfigRow> Function(AdSlotConfigRow row);

final adSlotConfigListProvider = Provider<AdSlotConfigList>((ref) {
  final client = ref.watch(apiClientProvider);
  return () async {
    final json = await client.get<List<dynamic>>('/admin/ads/slot-config');
    return json
        .map((o) => AdSlotConfigRow.fromJson((o as Map).cast<String, dynamic>()))
        .toList();
  };
});

final adSlotConfigSaveProvider = Provider<AdSlotConfigSave>((ref) {
  final client = ref.watch(apiClientProvider);
  return (row) async {
    final json = await client.put<Map<String, dynamic>>(
      '/admin/ads/slot-config/${row.slot}',
      body: row.toRequestJson(),
    );
    return AdSlotConfigRow.fromJson(json);
  };
});
```

- [ ] **Step 5: 상태에 필드 추가**

`ads_state.dart`의 `AdsState`에 `slotConfigs`를 더한다 — 생성자 파라미터, 필드, `copyWith` 세 곳 전부.

```dart
    this.slotConfigs = const [],
```
```dart
  final List<AdSlotConfigRow> slotConfigs;
```
```dart
    List<AdSlotConfigRow>? slotConfigs,
```
```dart
    slotConfigs: slotConfigs ?? this.slotConfigs,
```

상단에 `import '../data/ad_slot_config_row.dart';`를 추가한다.

- [ ] **Step 6: 컨트롤러 확장**

`ads_controller.dart`의 `load()`에서 슬롯 설정을 함께 읽고, 저장 메서드를 추가한다. 상단에 `import '../data/ad_slot_config_row.dart';`를 더한다.

`load()`의 `final enabled = …` 다음 줄에 추가한다. **슬롯 설정 조회 실패가 광고 관리 화면 전체를 죽이면 안 된다** — `load()`의 `catch (ApiException)`은 `phase: failed`로 가므로, 여기서 걸리면 기존 목록·전역 토글까지 함께 잃는다. 새 부가 기능이 기존 화면의 하드 의존이 되지 않도록 **자체 try로 감싸 빈 리스트로 폴백**한다.

```dart
      // 슬롯 설정은 부가 기능이다. 조회 실패가 광고 목록 화면을 무너뜨리지 않게
      // 여기서 흡수한다(백엔드 미배포·권한·네트워크).
      List<AdSlotConfigRow> configs = const [];
      try {
        configs = await ref.read(adSlotConfigListProvider)();
      } on ApiException {
        configs = const [];
      }
```

그리고 그 아래 `state = AdsState(...)` 생성자 호출에 인자를 추가:

```dart
        slotConfigs: configs,
```

> **`AdsState`를 새로 만드는 곳은 실측 4곳이다** — `load()`의 loading·`load()`의 loaded·`setSlotFilter`·`setStatusFilter`. `copyWith`가 아니라 생성자를 새로 호출하므로 **네 곳 전부**에 아래 줄을 넣어야 한다. 하나라도 빠지면 다이얼로그가 빈 목록을 본다.
>
> ```dart
>       slotConfigs: state.slotConfigs,
> ```
>
> (`load()`의 loaded 분기만 `slotConfigs: configs`이고, 나머지 세 곳은 `state.slotConfigs` 보존이다.)

저장 메서드를 클래스 끝에 추가:

```dart
  /// 슬롯 설정 1행을 저장하고 목록을 갱신한다.
  Future<void> saveSlotConfig(AdSlotConfigRow row) async {
    final saved = await ref.read(adSlotConfigSaveProvider)(row);
    state = state.copyWith(
      slotConfigs: [
        for (final c in state.slotConfigs)
          if (c.slot == saved.slot) saved else c,
      ],
    );
  }
```

- [ ] **Step 7: 화면에 버튼과 다이얼로그 추가**

`ads_page.dart`의 `DpPageHeader` `actions` 리스트에서 `FilledButton.icon(… '광고 생성' …)` **앞에** 버튼을 추가한다.

```dart
              OutlinedButton(
                onPressed: () => _openSlotConfig(context, n, s.slotConfigs),
                child: const Text('슬롯 설정'),
              ),
```

`_AdsPageState`에 메서드를 추가한다.

```dart
  Future<void> _openSlotConfig(
    BuildContext context,
    AdsController n,
    List<AdSlotConfigRow> configs,
  ) async {
    final result = await showDialog<List<AdSlotConfigRow>>(
      context: context,
      builder: (_) => _SlotConfigDialog(configs: configs),
    );
    if (result == null) return;
    for (final row in result) {
      await n.saveSlotConfig(row);
    }
  }
```

파일 끝에 다이얼로그를 추가한다. 상단 import에 `../data/ad_slot_config_row.dart`를 더한다.

```dart
// ---------------------------------------------------------------------------
// 슬롯 설정 다이얼로그 — 슬롯별 광고 소스와 애드센스 단위 ID
// ---------------------------------------------------------------------------
const _kSourceLabels = {
  'HOUSE': '하우스 광고',
  'ADSENSE': '애드센스',
  'OFF': '끄기',
};

class _SlotConfigDialog extends StatefulWidget {
  const _SlotConfigDialog({required this.configs});
  final List<AdSlotConfigRow> configs;
  @override
  State<_SlotConfigDialog> createState() => _SlotConfigDialogState();
}

class _SlotConfigDialogState extends State<_SlotConfigDialog> {
  late List<AdSlotConfigRow> _rows;
  late final Map<String, TextEditingController> _unitIds;

  @override
  void initState() {
    super.initState();
    _rows = [...widget.configs];
    _unitIds = {
      for (final r in _rows)
        r.slot: TextEditingController(text: r.adsenseSlotId ?? ''),
    };
  }

  @override
  void dispose() {
    for (final c in _unitIds.values) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('슬롯 설정'),
      content: SizedBox(
        width: 520,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (var i = 0; i < _rows.length; i++) _row(context, i),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('취소'),
        ),
        FilledButton(
          onPressed: () {
            final saved = [
              for (final r in _rows)
                r.copyWith(
                  adsenseSlotId: _unitIds[r.slot]!.text.trim().isEmpty
                      ? null
                      : _unitIds[r.slot]!.text.trim(),
                ),
            ];
            Navigator.of(context).pop(saved);
          },
          child: const Text('저장'),
        ),
      ],
    );
  }

  Widget _row(BuildContext context, int i) {
    final row = _rows[i];
    final isAdsense = row.source == 'ADSENSE';
    final unitIdEmpty = _unitIds[row.slot]!.text.trim().isEmpty;

    return Padding(
      padding: const EdgeInsets.only(bottom: DpSpacing.md),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(row.slot, style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: DpSpacing.xs),
          DropdownButtonFormField<String>(
            initialValue: row.source,
            decoration: const InputDecoration(labelText: '소스'),
            items: [
              for (final e in _kSourceLabels.entries)
                DropdownMenuItem(value: e.key, child: Text(e.value)),
            ],
            onChanged: (v) => setState(() {
              _rows[i] = row.copyWith(source: v ?? row.source);
            }),
          ),
          if (isAdsense) ...[
            const SizedBox(height: DpSpacing.xs),
            TextField(
              controller: _unitIds[row.slot],
              decoration: const InputDecoration(labelText: '애드센스 단위 ID'),
              onChanged: (_) => setState(() {}),
            ),
            if (unitIdEmpty)
              Padding(
                padding: const EdgeInsets.only(top: DpSpacing.xs),
                child: Text(
                  '단위 ID가 없으면 이 슬롯은 노출되지 않습니다',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: context.dpColors.warning,
                  ),
                ),
              ),
          ],
        ],
      ),
    );
  }
}
```

- [ ] **Step 8: 테스트를 돌려 통과를 확인**

```bash
cd /d/workspace/dpa/devpath-frontend/apps/admin && flutter test test/features/ads/
```

기대: 기존 5개 + 신규 2개 전부 PASS.

> 경고 문구 색은 `context.dpColors.warning`을 쓴다 — `packages/dp_design/lib/src/theme/dp_colors.dart:92`에 실재하는 토큰이다(라이트 `#A16207` · 다크 `#FCD34D`). 하드코딩 색을 쓰지 않는다.

- [ ] **Step 9: 모노레포 전체 검증**

```bash
cd /d/workspace/dpa/devpath-frontend && melos run analyze && melos run format && melos run test
```

기대: analyze 이슈 0건, **format이 `0 changed`**, 테스트 전부 통과.
`melos run format`은 CI와 같은 게이트다 — `(1 changed)`는 "고쳤다"가 아니라 **"어긋나 있다"**는 뜻이므로, 나오면 `melos run fix`로 고치고 다시 확인한다.

- [ ] **Step 10: 커밋과 PR**

```bash
git -C /d/workspace/dpa/devpath-frontend add apps/admin/lib/src/features/ads apps/admin/test/features/ads
git -C /d/workspace/dpa/devpath-frontend commit -m "feat(ads): admin에서 슬롯별 광고 소스를 설정한다"
git -C /d/workspace/dpa/devpath-frontend push -u origin feat/adsense-integration
gh pr create --repo DevPathAi/devpath-frontend --base develop --head feat/adsense-integration --title "feat(ads): 구글 애드센스 병행 도입" --body "슬롯별로 하우스 광고/애드센스/끄기를 admin에서 선택한다. 애드센스는 HtmlElementView로 <ins class=\"adsbygoogle\">를 삽입하고, 채워지지 않으면 접는다. 애드센스 가지에는 자체 노출·클릭 추적을 붙이지 않는다(구글 정책)."
```

- [ ] **Step 11: CI 녹색 확인 후 머지**

```bash
gh pr checks --repo DevPathAi/devpath-frontend feat/adsense-integration --watch
gh pr merge --repo DevPathAi/devpath-frontend feat/adsense-integration --merge
```

---

## Task 11: 브라우저 스모크 — 실제 DOM 동작 확인

**Files:** 없음 (검증 전용). 결함이 나오면 해당 Task로 돌아가 고친다.

**Interfaces:**
- Consumes: Task 1~10 전부
- Produces: 없음

> **위젯 테스트로는 여기까지 도달할 수 없다.** `apps/web` 테스트는 VM에서 돌아 stub 경로만 검증하므로, 실제 `<ins>` 삽입·접힘·재진입은 브라우저에서만 확인된다.
>
> **심사 전이므로 광고는 채워지지 않는 것이 정상이다.** 기대 상태는 "스크립트 로드 + `<ins>` 삽입 + `unfilled` → 접힘"까지다. 광고가 보이지 않는다고 결함으로 판정하지 않는다.

- [ ] **Step 1: 백엔드와 프론트 기동**

`docker compose up -d` 후 platform-svc와 gateway를 띄우고, web을 실API로 실행한다.

```bash
cd /d/workspace/dpa/devpath-frontend/apps/web && flutter run -d chrome --dart-define-from-file=.env.local
```

- [ ] **Step 2: 서버가 살아 있는지 먼저 확인 (오진 방지)**

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/health
```

기대: `200`. **서버가 죽은 상태로 캡처하면 아이콘이 □로 깨진 화면이 찍혀 구현 결함으로 오진하기 쉽다.** 상태코드를 먼저 본다.

- [ ] **Step 3: 슬롯을 애드센스로 전환**

admin(`/ads`)에서 「슬롯 설정」 → `DASHBOARD_TOP`을 **애드센스**로 바꾸고 실제 광고 단위 ID를 입력해 저장한다.

- [ ] **Step 4: `<ins>` 삽입 확인**

브라우저 devtools 콘솔에서:

```js
document.querySelectorAll('ins.adsbygoogle').length
```

기대: `1`. `0`이면 Task 7·8의 배선을 다시 본다.

- [ ] **Step 5: 접힘 확인**

```js
document.querySelector('ins.adsbygoogle').getAttribute('data-ad-status')
```

기대: `"unfilled"`(심사 전). 그리고 대시보드 상단에 **빈 상자가 남아 있지 않은지** 눈으로 확인한다.

- [ ] **Step 6: 재진입 시 중복 삽입 에러와 누수가 없는지 확인**

대시보드 → 다른 화면 → 대시보드로 3회 왕복한 뒤 콘솔 에러를 본다.

기대: `"already have ads in them"` 에러가 **없다**. 나오면 Task 7의 `_seq++` viewType 생성이 인스턴스마다 도는지 확인한다.

이어서 **누수**도 함께 본다. `_seq++`는 인스턴스마다 새 viewType을 등록하는데 Flutter web에는 unregister API가 없어 viewFactory가 영구 누적된다. Monaco는 샌드박스 1곳·단일 인스턴스라 문제되지 않았지만, 광고는 3슬롯 × 네비게이션마다 등록된다.

```js
document.querySelectorAll('ins.adsbygoogle').length
```

기대: 왕복 후에도 **화면에 살아 있는 슬롯 수만큼만** 남는다(대시보드면 `1`). 계속 증가하면 `dispose()`의 `<ins>` 제거가 동작하지 않는 것이므로 Task 8의 심 `dispose`를 확인한다. viewType 등록 누적 자체는 이번 범위에서 해소하지 않되, **관측 결과를 Step 10 보고서에 수치로 남긴다.**

- [ ] **Step 7: 애드블록 상태 확인**

애드블록 확장을 켠 채로 새로고침한다.

기대: 슬롯이 접힌 채로 남고, 레이아웃이 깨지지 않는다.

- [ ] **Step 8: 스크립트 로드 확인**

devtools Network 탭에서 `adsbygoogle.js` 요청 상태를 본다.

기대: `200`. `404`면 퍼블리셔 ID가 잘못됐다(Task 8의 치환 확인).

- [ ] **Step 9: 슬롯을 하우스로 원복**

admin에서 `DASHBOARD_TOP`을 **하우스 광고**로 되돌리고, 기존 하우스 광고가 정상 렌더되며 노출·클릭 이벤트가 여전히 기록되는지 확인한다.

- [ ] **Step 10: 결과 기록**

확인한 항목과 결과를 `devpath-platform-svc/docs/superpowers/reports/2026-08-09-adsense-smoke.md`에 남기고 커밋한다. 관측된 상태(`unfilled` 등)를 그대로 적는다.

---

## 남은 것 (이 계획의 범위 밖)

- **애드센스 심사 신청과 승인.** 라이브 사이트가 전제인데 AWS 정지로 `leva.ai.kr`이 내려가 있다. 재가동 이후 별건이다.
- 자동 광고(Auto ads), 애드센스 실적 대시보드 연동, 광고 단위 생성 자체(구글 콘솔에서 수행).
- 슬롯 카탈로그 단일 출처화(`AdSlot.java` + CHECK 3곳 중복).
