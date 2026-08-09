# 구글 애드센스 병행 도입 — 설계

- 작성일: 2026-08-09
- 관련 레포: `devpath-shared` · `devpath-platform-svc` · `devpath-frontend`
- 선행 스펙: [2026-07-22-beta-ads-design.md](./2026-07-22-beta-ads-design.md) (하우스/스폰서 광고)

## 1. 배경과 결정의 성격

기존 광고 기능은 하우스/스폰서 광고를 자체 서빙한다(`advertisement` 테이블 · `GET /ads?slot=` · `AdSlotWidget`).
사용자 요구로 **구글 애드센스를 병행 도입**한다. 기존 자체 광고는 제거하지 않고 함께 운영한다.

원 설계(2026-07-22)는 외부 광고 네트워크를 기각했고, 그 사유는 Flutter Web CanvasKit과의 상성이었다.
이번 착수 전 코드 실측으로 **그 기각 사유가 약해졌음**을 확인했다:

| 확인한 것 | 근거 |
|---|---|
| DOM 임베딩 선례 | `monaco_editor_view_web.dart`가 `dart:ui_web`의 `platformViewRegistry.registerViewFactory` + `HtmlElementView`로 실제 DOM을 캔버스 안에 넣고 있다 |
| 외부 스크립트 선례 | `apps/web/web/index.html`이 CDN 스크립트를 싣고 `window.createDevpathEditor` 심(shim)을 노출한다 |
| 조건부 import 패턴 | `monaco_editor_view.dart` / `_stub.dart` / `_web.dart` 3분할이 확립돼 있다 |
| 광고 슬롯 | `AdSlotWidget(slot:)` 사용처는 정확히 3곳 — `DASHBOARD_TOP` · `COMMUNITY_FEED` · `CONTENT_PAGE` |

즉 애드센스가 요구하는 메커니즘(외부 스크립트 + DOM 요소 삽입)은 이 코드베이스에 이미 증명돼 있다.

### 확정된 요구사항

| 항목 | 결정 |
|---|---|
| 하우스/애드센스 공존 | **admin에서 슬롯별로 선택** |
| 애드센스 계정 상태 | 계정 보유, **심사 전/진행 중** |
| 빈 자리 처리 | **접어버린다** (기존 fail-silent와 일관) |
| 광고 단위 ID(`data-ad-slot`) 관리 | **admin에서 슬롯별 입력** |
| 클라이언트 전달 방식 | **기존 `GET /ads` 응답 확장** (설정 전용 엔드포인트 신설 아님) |

### 제약 (선택이 아님)

**애드센스 광고 단위에 자체 노출·클릭 추적을 붙이지 않는다.** 구글 정책이 광고 클릭 개입과 인위적 노출 부풀리기를 금지한다.
기존 `VisibilityDetector` 노출 카운팅과 `ad_daily_stats`는 **하우스 광고 전용으로 남고**, 애드센스 실적은 구글 콘솔에서 확인한다.

---

## 2. 데이터 모델과 서빙 계약

### 2.1 신규 테이블 `ad_slot_config` (devpath-shared)

파일: `src/main/resources/db/migration/V202608091001__ad_slot_config.sql`

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
  ('DASHBOARD_TOP','HOUSE'), ('COMMUNITY_FEED','HOUSE'), ('CONTENT_PAGE','HOUSE');
CREATE TRIGGER ad_slot_config_set_updated_at BEFORE UPDATE ON ad_slot_config
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
```

- **시드 3행 전부 `HOUSE`** — 마이그레이션 직후 동작이 지금과 정확히 같다.
- `ad_settings`의 전역 토글은 그대로 최상위 스위치로 남는다.
- 슬롯 카탈로그는 이미 `AdSlot.java`와 `advertisement.chk_ad_slot`에 중복돼 있고 이것이 세 번째다. 기존 관행을 따르되 **단일 출처화는 이번 범위 밖**이다.

### 2.2 `GET /ads?slot=X` 응답 — 판별 유니온

| 상태 | 응답 |
|---|---|
| 전역 토글 off | 204 |
| 슬롯 `OFF` | 204 |
| `HOUSE` + 적격 광고 있음 | 200 `{"type":"HOUSE","ad":{id,title,imageUrl,linkUrl,slot}}` |
| `HOUSE` + 적격 광고 없음 | 204 |
| `ADSENSE` + 단위 ID 있음 | 200 `{"type":"ADSENSE","adsenseSlotId":"1234567890"}` |
| `ADSENSE` + 단위 ID 없음 | 204 (미설정이면 접힌다) |

`AdView`에 nullable 필드를 얹지 않고 **봉투를 씌우는 이유**: 그렇게 하면 `type=ADSENSE`인데 `title`이 필수인 표현 불가능한 상태가 생긴다.
대가로 프론트 파싱이 바뀌고 관련 테스트가 함께 움직인다(§5.1에 실측된 파급 범위).

### 2.3 퍼블리셔 ID와 단위 ID의 위치

| 값 | 위치 | 이유 |
|---|---|---|
| 퍼블리셔 ID (`ca-pub-…`) | `apps/web/web/index.html` `<head>` (+ `ads.txt`) | 계정당 하나로 사실상 바뀌지 않고 **공개 값**이다. 무엇보다 **심사 중 구글 크롤러가 페이지에서 스크립트를 찾아야 하므로** 지연 주입하면 안 된다 |
| 광고 단위 ID (`data-ad-slot`) | admin (`ad_slot_config.adsense_slot_id`) | 광고 단위를 늘리거나 바꿀 때마다 달라진다 |

### 2.4 `ads.txt`

다음 한 줄을 둔다.

```
google.com, pub-XXXXXXXXXXXXXXXX, DIRECT, f08c47fec0942fa0
```

> **2026-08-10 정정.** 초안은 "Flutter 빌드가 산출물 루트로 복사하므로 홈페이지 레포는 건드리지 않는다"고 썼으나 **틀렸다.** gitops ingress 실측상 웹앱은 `app.leva.ai.kr`이고 루트 `leva.ai.kr`은 별도 레포 `devpath-home-page`(CF Pages)다. `ads.txt`는 페이지 호스트의 **루트 도메인**에서 조회되는 것이 표준이므로 서브도메인에만 두면 찾지 못한다.
>
> 따라서 **양쪽에 둔다**: `apps/web/web/ads.txt`(앱) + `devpath-home-page/ads.txt`(루트). 후자는 `build.mjs`의 `DEPLOY_ENTRIES` 화이트리스트에 `'ads.txt'`를 추가해야 실제로 배포된다.

---

## 3. 백엔드 (devpath-platform-svc)

### 3.1 도메인 타입

```java
public sealed interface AdSlotContent {
  record House(AdView ad) implements AdSlotContent {}
  record Adsense(String adsenseSlotId) implements AdSlotContent {}
}
```

Java 21 sealed로 표현 불가능한 상태를 없앤다. `AdServeService.serve()`의 반환은 `Optional<AdView>` → `Optional<AdSlotContent>`.

판정 순서: **전역 토글 → 슬롯 `source` → (HOUSE면) 기존 가중치 랜덤 선택**.
기존 `findEligible` + `pickWeighted` 로직은 HOUSE 가지 안으로 그대로 들어가며 **동작이 바뀌지 않는다**.

wire 표현(`{"type":…}`)은 **`AdController` 한 곳에서만** `switch`로 조립한다. Jackson 다형 직렬화 설정을 쓰지 않는다 — 잘못된 필드 조합이 생길 수 있는 지점을 하나로 묶기 위해서다.

### 3.2 admin API

| 메서드 | 경로 | 내용 |
|---|---|---|
| `GET` | `/admin/ads/slot-config` | 3행 반환 `[{slot, source, adsenseSlotId}]` |
| `PUT` | `/admin/ads/slot-config/{slot}` | body `{source, adsenseSlotId}` → 갱신된 행 |

기존 `AdminAdController`(`/admin/ads`, `SecurityConfig`의 `/admin/**` `hasRole("ADMIN")`로 보호)에 추가한다.

**검증 규칙:**
- `source`가 `HOUSE|ADSENSE|OFF` 외의 값이면 400.
- **`source=ADSENSE`인데 `adsenseSlotId`가 비어도 400이 아니라 저장을 허용한다.** §2.2에서 그 조합을 "204로 접힌다"로 정의했으므로, 400을 던지면 그 분기가 도달 불가능한 죽은 코드가 된다. admin UI가 경고 문구로 안내한다.
- `adsenseSlotId`는 trim하고, 빈 문자열은 `null`로 정규화한다.
- `source`가 `HOUSE`·`OFF`여도 입력된 `adsenseSlotId`는 보존한다(되돌리기 편의).

---

## 4. 프론트엔드 (devpath-frontend)

### 4.1 계약 파싱 — sealed 유니온

`apps/web/lib/src/features/ads/data/`:

```dart
sealed class AdSlotContent {}
class HouseAd     extends AdSlotContent { final AdView ad; }
class AdsenseUnit extends AdSlotContent { final String adsenseSlotId; }
```

`adFetchProvider`의 타입이 `Future<AdView?> Function(String)` → `Future<AdSlotContent?> Function(String)`으로 바뀐다.
204 · 파싱 실패 · 네트워크 실패 · **알 수 없는 `type`** 은 전부 `null`(기존 fail-silent 유지, 전방 호환).

`AdView` 클래스 자체는 봉투 안 `ad` 객체 파싱에 그대로 쓰이므로 변경 없다.

### 4.2 `AdSlotWidget` 분기

`build`는 세 갈래다.

- `HouseAd` → **지금 코드 그대로.** `VisibilityDetector` · `adEventProvider` · `InkWell` 전부 유지.
- `AdsenseUnit` → `AdSenseUnitView(slotId:)`만 그린다. **이 가지에는 측정 호출이 존재하지 않는다**(§1 제약).
- `null` → `SizedBox.shrink()`.

### 4.3 DOM 삽입 — Monaco 패턴

`adsense_unit_view.dart`(추상) / `_stub.dart`(`SizedBox.shrink()`) / `_web.dart`(`registerViewFactory` + `HtmlElementView`).

**조건부 import는 선택이 아니라 필수다.** `apps/web`의 테스트는 VM에서 실행되므로 `dart:ui_web`·`dart:js_interop`을 직접 import하면 테스트가 깨진다. Monaco가 정확히 이 이유로 3분할돼 있다.

`index.html`에 심을 추가한다:

```js
window.createDevpathAdUnit = function (container, slotId, onResolved) { … }
```

심은 `<ins class="adsbygoogle" data-ad-client="ca-pub-…" data-ad-slot="{slotId}">`를 만들어 컨테이너에 넣고 `(adsbygoogle = window.adsbygoogle || []).push({})`를 호출한다.

**두 가지 함정을 명시적으로 피한다:**
1. **viewType은 `State.initState`에서 1회만 생성한다.** 함수형 `build`에서 매 rebuild마다 만들면 viewFactory가 무한 증식한다(Monaco에 기록된 함정).
2. **매 인스턴스 `_seq++`로 새 viewType을 쓴다.** 같은 `<ins>`를 재사용하면 구글이 "All `ins` elements … already have ads in them"으로 거부한다.

### 4.4 높이 — 콜백 기반 동적 확장

`HtmlElementView`는 부모가 준 제약을 채울 뿐이라 Flutter는 광고 높이를 모른다.

**높이 0으로 시작한다.** 심은 `<ins>`에 `MutationObserver`를 걸어 `data-ad-status` 속성 변화를 관측하고(폴링 아님), 관측 즉시 `onResolved(status, height)`를 1회 호출한 뒤 observer를 해제한다. 8초 안에 변화가 없으면 타임아웃으로 `onResolved('unfilled', 0)`을 호출한다.

> **2026-08-10 정정 — 두 가지를 고쳤다.**
>
> 1. **`push({})`는 컨테이너가 폭을 가진 뒤에 호출한다.** 초안은 `registerViewFactory` 콜백 안에서 즉시 push했는데, 그 시점 div는 아직 레이아웃 전이라 폭이 0이고 애드센스는 `availableWidth=0`으로 거부한다. `requestAnimationFrame`으로 `container.offsetWidth > 0`이 될 때까지 기다린 뒤 push한다. (Monaco 선례는 이 문제를 검증해 주지 않는다 — Monaco는 컨테이너 크기를 요구하지 않고, 그래서 `height:100%`를 주고 있다. 「DOM 삽입이 가능하다」와 「애드센스가 그 안에서 채워진다」는 다른 주장이다.)
> 2. **`window.adsbygoogle` 부재를 즉시 `unfilled`로 처리하지 않는다.** 애드센스 스크립트는 `async`라 로드 전에 배열이 없는 것이 정상이고, 표준 관용구 `(window.adsbygoogle = window.adsbygoogle || []).push({})`가 바로 그 대기 큐를 만들기 위한 것이다. 초안의 가드는 스크립트가 아직 안 온 정상 상태를 접어버리는 레이스였다. 애드블록·차단·무응답은 전부 **타임아웃 하나로** 흡수한다(그래서 3초 → 8초).

| 심이 관측한 것 | 위젯 동작 |
|---|---|
| `filled` | 실제 높이로 `SizedBox(height:)` 확장 |
| `unfilled` | 접는다 (`SizedBox.shrink()`) |
| 애드블록·스크립트 차단 (응답 자체가 없음) | 타임아웃으로 접는다 |
| 8초 타임아웃 | 접는다 |

고정 높이 대신 이 방식을 택한 이유: 고정 높이는 심사 대기·애드블록 상태에서 **빈 상자를 남겨** 「빈 자리는 접는다」 결정과 정면 충돌한다.
대가는 심 로직 증가와, 광고가 채워질 때 레이아웃이 한 번 밀리는 것이다.

### 4.5 admin UI

기존 `AdminAdsPage`의 `DpPageHeader` actions에 **「슬롯 설정」 버튼**을 추가하고, 다이얼로그에서 3행을 편집한다.

- 행마다: 슬롯명 + 드롭다운(**하우스 광고 / 애드센스 / 끄기**) + 단위 ID 입력(애드센스 선택 시에만 활성).
- 애드센스인데 ID가 비면 「단위 ID가 없으면 이 슬롯은 노출되지 않습니다」 경고를 표시한다.
- 상태는 기존 관행대로 `AdsState.slotConfigs` + `AdsController`에 얹는다.

상시 노출 카드가 아니라 다이얼로그를 택한 이유: 자주 바꾸는 설정이 아니고, 상단에 고정하면 광고 목록 `DpDataTable` 공간을 계속 잠식한다.

---

## 5. 검증

### 5.1 실측된 기존 테스트 파급

**백엔드** — `AdServeServiceTest`의 3개 테스트 모두 `new AdServeService(repo, settings, new Random(0))`를 직접 호출한다.
슬롯 설정 조회가 생성자에 추가되면 **3개 전부 컴파일 불가**다(반환 타입이 아니라 생성자 시그니처 때문이며, `weightedSelectionPicksByRandom`은 `Optional<AdView>`도 함께 깨진다).

**`AdControllerTest`는 `/ads/{id}/events`만 다루고, 서빙 응답 형태를 단언하는 테스트가 없다.**
따라서 봉투 도입은 "깨진 테스트를 고치는" 일이 아니라 **없던 계약 테스트를 신설하는** 일이다.

**프론트** — 파급이 예상보다 작다.
- 수정 대상: `ad_slot_widget_test.dart`의 `_ad()` 헬퍼와 override 2곳 → `HouseAd(...)`로 감싼다.
- **수정 불필요**: 광고를 끄려고 `(slot) async => null`을 쓰는 무관 테스트 4곳(`content_page_test:364` · `content_practice_action_color_test:45` · `dashboard_header_test:14` · `page_header_scroll_test:199`). `Null`이 모든 nullable 타입의 서브타입이라 그대로 컴파일·통과한다.

### 5.2 신설 테스트 (Test-First)

| 대상 | 케이스 |
|---|---|
| `AdServeServiceTest` | 슬롯 OFF→empty · ADSENSE+ID→`Adsense` · ADSENSE+ID없음→empty · 기존 HOUSE 3케이스 유지 |
| `AdControllerTest` | `{"type":"HOUSE","ad":{…}}` · `{"type":"ADSENSE","adsenseSlotId":…}` · 204 (jsonPath) |
| `AdminAdControllerTest` | slot-config GET 3행 · PUT 갱신 · 빈 문자열→null · 잘못된 source→400 |
| `ads_source_test.dart` | HOUSE/ADSENSE 파싱 · 알 수 없는 type→null · 204→null |
| `ad_slot_widget_test.dart` | HOUSE 렌더 유지 · **ADSENSE 가지에서 `adEventProvider` 호출 0회**(정책 가드) · null 접힘 |
| admin `ads_page_test.dart` | 다이얼로그 열림 · ADSENSE 선택 시 ID 필드 활성 · ID 비면 경고 문구 |

### 5.3 브라우저 스모크 (위젯 테스트로 도달 불가)

1. `<ins class="adsbygoogle">`가 실제로 DOM에 삽입되는가
2. `data-ad-status="unfilled"` → 접힘 — **심사 전이므로 이것이 정상 경로다**
3. 슬롯 재진입 시 "already have ads in them" 콘솔 에러가 없는가
4. 애드블록을 켠 상태에서 접히는가
5. 애드센스 스크립트가 200으로 로드되는가(퍼블리셔 ID 오타면 404)

**판별 함정:** 서버가 죽으면 아이콘이 □로 깨진 화면이 찍혀 구현 결함으로 오진하기 쉽다. 캡처 전에 **curl 상태코드 + 콘솔 에러로 먼저 판별**한다.

### 5.4 레포별 순서

`devpath-shared`(마이그레이션) → `devpath-platform-svc`(서빙·admin API) → `devpath-frontend`(web 렌더 + admin UI + `ads.txt`).

shared가 임계경로다. 과거 전례대로 `gh workflow run publish.yml --ref develop` 수동 발행이 필요할 수 있다.

---

## 6. 선행조건과 범위 밖

### 착수 게이트

**퍼블리셔 ID(`ca-pub-…`)의 실제 값이 필요하다.** `index.html`과 `ads.txt` 양쪽에 들어가므로, 값이 없으면 프론트 Task를 시작할 수 없다. 공개 값이라 커밋에 문제는 없다.

### 이 스펙의 완료 조건이 아닌 것

**심사 승인과 실광고 노출 확인.** 애드센스 심사는 라이브 사이트가 전제인데 AWS 정지로 `leva.ai.kr`이 내려가 있다.
코드 병합과 §5.3 스모크까지가 이번 범위이고, 승인은 재가동 이후 별건이다.

### 범위 밖

- 자동 광고(Auto ads)
- 애드센스 실적 대시보드 연동
- 광고 단위 생성 자체(구글 콘솔에서 수행)
- 슬롯 카탈로그 단일 출처화
- `ad_daily_stats`·`VisibilityDetector`는 하우스 전용으로 **변경 없이** 유지
