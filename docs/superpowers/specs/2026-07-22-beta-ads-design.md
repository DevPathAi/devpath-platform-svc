# 베타 무료기간 광고(하우스/스폰서) 설계

- 날짜: 2026-07-22
- 레포: devpath-platform-svc(오너) + devpath-shared(마이그레이션) + devpath-frontend(apps/web·apps/admin)
- 상태: 브레인스토밍 승인됨

## 배경 / 목표

베타 무료기간 동안 DevPath 데모 앱(leva.ai.kr)에 **자체 하우스/스폰서 광고**를 노출하고 admin에서 관리한다. 결제(PortOne)는 이번 범위 밖이며, 광고가 무료기간 수익화 수단이다. 외부 광고 네트워크(AdSense 등)는 Flutter Web(CanvasKit=canvas 렌더) 상성·베타 소규모 트래픽·승인 부담으로 **채택하지 않는다**. 우리가 소재를 관리하고 우리 슬롯에 서빙한다.

## 결정 사항 (브레인스토밍)

- **모델**: 자체 하우스/스폰서 광고(외부 네트워크 미도입).
- **소유 서비스 = platform-svc의 신규 `ads` 모듈**(Approach B). 근거: ①베타 노드(t3.xlarge 16GB, 8 svc+Kafka+Redis)에 신규 서비스 추가 회피 ②광고 이미지 업로드가 platform 기존 `S3ObjectStorage`+`StoredFileValidator`(avatar 패턴) 재사용 ③admin 인증·게이트웨이 `/admin/**`·`/users/**` 라우트 재사용.
- **슬롯 3종(고정 enum)**: `DASHBOARD_TOP`(대시보드 상단 배너) · `COMMUNITY_FEED`(커뮤니티 5번째 게시글 뒤 네이티브 카드) · `CONTENT_PAGE`(콘텐츠 상세 + 학습경로 페이지 공유).
- **관리 범위**: 소재 CRUD+슬롯지정+활성토글 · 게재기간 스케줄(start/end) · 다중 광고 로테이션(가중치) · 노출/클릭 측정 리포트.
- **무료기간 게이팅**: admin **전역 토글** + 서빙 `userShouldSeeAds(user)` predicate(지금 항상 true=전원 free, 후속 유료 티어 시 `tier==FREE`로만 확장 — 결제 미의존).
- **측정**: `ad_daily_stats` **직접 UPSERT**(베타 규모엔 Kafka/Outbox 과설계 — outbox가 쓰기 2배). Kafka 집계는 실운영 스케일 후속.
- **범위 밖(YAGNI)**: 사용자 세그먼트 타게팅, 프리퀀시 캡, 중복제거/봇필터, A/B, 외부 네트워크, 다중 피드 삽입.

## 데이터 모델 (devpath-shared 중앙 Flyway)

### `advertisement`
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | bigserial PK | |
| title | varchar(200) not null | 관리용 제목/스폰서 표기 |
| image_url | varchar(1000) null | 스토리지 공개 URL. null=텍스트형 |
| link_url | varchar(1000) not null | 클릭 목적지(https) |
| slot | varchar(30) not null CHECK | DASHBOARD_TOP·COMMUNITY_FEED·CONTENT_PAGE |
| weight | int not null default 1 CHECK(weight>=1) | 로테이션 가중치 |
| status | varchar(20) not null default 'ACTIVE' CHECK | ACTIVE·PAUSED |
| starts_at | timestamptz null | null=즉시 |
| ends_at | timestamptz null | null=무기한 |
| created_at·updated_at | timestamptz not null default now() | updated_at 트리거 |

인덱스: `(slot, status)`.

### `ad_settings` (단일행 전역 설정)
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | int PK CHECK(id=1) | 단일행 강제 |
| enabled | boolean not null default true | 전역 광고 on/off |
| updated_at | timestamptz not null default now() | |

마이그레이션에서 `INSERT (1, true)` 시드.

### `ad_daily_stats`
| 컬럼 | 타입 | 비고 |
|---|---|---|
| ad_id | bigint not null FK→advertisement(id) ON DELETE CASCADE | |
| stat_date | date not null | UTC 날짜 |
| impressions | bigint not null default 0 | |
| clicks | bigint not null default 0 | |
| PK(ad_id, stat_date) | | UPSERT 대상 |

## 서빙 API (웹 대면, 인증 유저)

- `GET /ads?slot={SLOT}` → gateway→platform.
  - **적격 필터**: `status=ACTIVE` AND `ad_settings.enabled` AND (`starts_at IS NULL OR starts_at<=now()`) AND (`ends_at IS NULL OR ends_at>now()`) AND `userShouldSeeAds(user)`.
  - **선택**: 적격 광고 중 **가중치 랜덤** 1개. `java.util.Random` 주입(테스트 결정화).
  - **응답 200**: `{id, title, imageUrl, linkUrl, slot}` (weight/status 미노출).
  - **빈 경우 204 No Content**(전역 off/적격 없음) → 웹 무렌더.
- `POST /ads/{id}/events` body `{type: "IMPRESSION"|"CLICK"}` → `ad_daily_stats` 직접 UPSERT
  (`INSERT ... ON CONFLICT (ad_id, stat_date) DO UPDATE SET impressions=impressions+1` 또는 clicks). 광고 없으면 404. 성공 **202**. 유실 허용.

게이트웨이: `/ads/**` → platform 라우트 신규 추가.

## Admin API (`/admin/ads/**`, ADMIN 롤)

- `GET /admin/ads` (slot/status 필터) — 전 필드
- `POST /admin/ads` · `PUT /admin/ads/{id}` · `DELETE /admin/ads/{id}` (stats cascade)
- `POST /admin/ads/{id}/image` — 멀티파트 → `S3ObjectStorage`+`StoredFileValidator`(png/jpeg/webp, 2MB) 재사용 → image_url. 스토리지 미가용 시 503 STORAGE_UNAVAILABLE(광고는 이미지 없이도 생성 가능).
- `GET /admin/ads/settings` · `PUT /admin/ads/settings {enabled}` — 전역 토글
- `GET /admin/ads/{id}/stats?from=&to=` — `ad_daily_stats` 일별 노출/클릭

platform SecurityConfig: `/ads/**`=인증, `/admin/ads/**`=ADMIN 롤.

## 프론트 (devpath-frontend)

### dp_core
- `Ad` 모델(freezed) · `AdSlot` enum(dashboardTop/communityFeed/contentPage) · `AdSource`(`getAd(slot)`→Ad?, `recordEvent(adId, type)`).

### apps/web (신규 `ads` feature)
- 위젯 3종: `AdBannerWidget`(대시보드 상단) · `AdFeedCardWidget`(커뮤니티) · `AdContentWidget`(콘텐츠/경로).
- 공통 동작: 마운트 시 슬롯 광고 fetch → 204/null이면 `SizedBox.shrink` → 최초 렌더 1회 IMPRESSION 발행(재발사 가드) → 탭 시 CLICK 발행 후 `link_url` 오픈.
- `adProvider(AdSlot)` Riverpod family.
- 삽입 지점: dashboard_page 상단 · community 목록 5번째 뒤 · content_page + path_page.

### apps/admin (신규 `ads` feature)
- `AdsSource`(CRUD·이미지·settings·stats). 화면: 목록(+전역 토글 스위치, 오늘 노출/클릭), 생성/수정 폼(제목·슬롯·링크·가중치·상태·기간·이미지), 통계. 셸 라우트·nav 추가. 기존 reports/users 구조 답습(application/data/presentation/state).

## 에러 처리

광고는 **절대 페이지를 깨지 않는다(fail-silent)**.
- 서빙 실패/204 → 무렌더(로그만). 이벤트 POST 실패 → 조용히 무시(fire-and-forget).
- 이미지 업로드 스토리지 미가용 → 503 표시, 이미지 없이 생성 가능(텍스트형).
- 링크 누락/부정·필수 누락 → 400 VALIDATION_FAILED. 이벤트 대상 광고 없음 → 404.

## 테스트

- 백엔드(platform):
  - `AdServeService`: 적격 필터(스케줄·상태·전역 토글·티어 predicate) + 가중치 선택(`Random` 주입) — 유닛.
  - `AdEventService`: UPSERT 증분 — @SpringBootTest/JPA. **application-test.yml hikari.maximum-pool-size=4**(다수 컨텍스트 커넥션 초과 flake 예방).
  - admin CRUD 컨트롤러 테스트, DB CHECK 제약(slot/status/weight)과 일치.
  - 이미지 업로드: ObjectStorage 목/미가용(503) — avatar 테스트 패턴.
- 프론트:
  - web 위젯: 광고 있음 렌더·204 무렌더·impression 1회·클릭 오픈.
  - admin 폼·목록.
  - 계약: web `AdSource` 경로·DTO ↔ platform 엔드포인트.
- shared: Flyway 마이그레이션 3테이블 + `ad_settings` 시드. 머지 후 `gh workflow run publish.yml --ref develop`.

## Phase 분해 (각 별도 spec→plan→구현)

- **P1 백엔드(platform + shared 마이그레이션)**: 스키마 + 서빙(`GET /ads`) + 이벤트(`POST /ads/{id}/events`) + admin API(CRUD·이미지·settings·stats) + SecurityConfig·게이트웨이 라우트.
- **P2 admin UI**: apps/admin `ads` feature.
- **P3 web 위젯·슬롯 통합**: dp_core `Ad`/`AdSource` + 3위젯 + 삽입.

## 배포 연계

- shared 마이그레이션은 [[devpath-deploy-homepage-demo-roadmap]] WS-D 실배포 전/중 반영. 전역 토글로 베타 종료 시 광고 off. 실운영 전환 시 측정 파이프라인을 Kafka 집계로 승격.

## 리스크

- R1 impression naive 카운팅으로 부풀림 가능(베타 허용, 후속 캡/dedup).
- R2 platform 비대화 — ads 모듈로 격리해 완화. 트래픽↑ 시 전용 서비스 분리 여지.
- R3 광고 이미지 스토리지 의존 — 미가용 시 텍스트형 폴백으로 완화.
