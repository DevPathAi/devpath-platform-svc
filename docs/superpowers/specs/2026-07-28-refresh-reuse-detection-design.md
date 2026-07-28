# Refresh 토큰 재사용 감지(Reuse Detection) 설계

- 날짜: 2026-07-28
- 상태: 사용자 승인 (구현 전)
- 대상: `devpath-platform-svc` `RefreshTokenStore` (+ `AuthProperties`)
- 관련: 본 설계는 [2026-07-27-refresh-rotate-grace-design.md](2026-07-27-refresh-rotate-grace-design.md) §6.2에서 후속으로 분리된 항목이다(핸드오프 `handoff-2026-07-27-auth-3defects-resolved-e2e-pending.md` 후속 백로그 #2). grace(유예창)의 **보안 짝**이다.

## 1. 배경 — grace의 보안 짝

어제 도입한 회전 유예창(grace, 기본 30s)은 동시 refresh가 401로 세션을 파괴하지 않도록 **직전 토큰의 짧은 재사용을 의도적으로 허용**한다. 표준 절충(Auth0 "Refresh Token Reuse Interval")이지만, 그 이면의 짝인 **재사용 감지**가 아직 없다.

**근본 갭** (코드 실측으로 확정):

- 현재 `refresh:<hash>` 값은 `grace:<userId>`이고 **TTL이 곧 grace(30s)**다. 유예창이 지나면 **키 자체가 Redis에서 소멸**한다(`RefreshTokenStore.rotate()` 62~69행).
- 따라서 회전된 토큰을 유예창 밖에서 다시 제시하면 서버는 `GET`에서 `null`을 받아 **401만 반환**하고 끝난다(`AuthController:73~74`). 이 `null`은 **"정상 만료"와 "탈취 토큰 재사용"을 구분하지 못한다.**
- 표준 회전 모델(OAuth 2.0 Security BCP §4.13)에서 **이미 회전된(소비된) 토큰의 유예창 밖 재제시**는 토큰 탈취의 대표 신호다: 공격자가 refresh 토큰을 가로챈 뒤, 정상 사용자가 이미 그 토큰을 회전시켜 지나간 상태에서 스테일 토큰이 제시되는 상황.

**핵심 아이디어**: 회전된 토큰의 **묘비(tombstone)를 grace보다 오래 남겨** 트립와이어로 쓴다. 유예창 밖에서 그 토큰이 재제시되면 탈취로 간주하고 **해당 사용자의 전 세션을 폐기**한다.

## 2. 목표 / 비목표

**목표**

- 유예창 밖 회전-토큰 재사용을 감지해 `revokeAll(userId)`로 **전 세션 폐기**(계정삭제 경로가 이미 쓰는 프리미티브 재사용, `AccountService:25`).
- **서버 수정만으로 완결**(클라이언트 배포 불요). `AuthController` 시그니처·컨트롤러 로직 무변경 — `rotate()`는 재사용 시에도 기존과 동일하게 `empty`를 반환(→ 401)하고, 폐기는 스토어 내부에서 수행.
- 즉시 켜되(enforce), **킬스위치**로 today 동작으로 즉시 되돌릴 수 있게 한다.

**비목표 (후속 백로그로 분리)**

- frontend refresh single-flight(#1).
- `BetaGate.admit()` ADMIN 조기 return의 status `BETA_PENDING` 방치 정합(#3).
- Redis 영속성/재시작 내성(#4).
- 토큰 **family/lineage** 기반 **기기별 정밀 폐기**. 요구가 "전량 폐기"라 과설계(YAGNI) — **채택하지 않음**.

## 3. 설계 (Approach A — 단일 키 진화)

현 코드가 이미 `refresh:<hash>` 값에 `grace:` 접두를 오버로드하고 있으므로, 그 단일 키 모델을 그대로 확장한다(2키 분리안 대비 변경 표면 최소).

### 3.1 저장 값 포맷

| 값 | 의미 | TTL |
|---|---|---|
| `<userId>` | 현행(살아있는) 토큰 | `refreshTtl`(14d) |
| `grace:<userId>:<rotatedAtEpochMillis>` | 회전으로 대체된 토큰 = **묘비**. `rotatedAt` 기준 grace 이내=정상 동시-refresh, 초과=재사용 신호 | **`refreshTtl`(14d)** |

핵심 변경: grace 마커의 TTL이 **grace(30s) → `refreshTtl`(14d)**. 유예창이 지나도 묘비가 남아 트립와이어로 동작한다. 유예/재사용 판정은 이제 **TTL 소멸이 아니라 저장된 `rotatedAt`과 현재 시각의 차**로 한다.

### 3.2 `rotate(oldToken)` 규칙

`GET refresh:<oldHash>` → `value` 분기:

1. `null` → `empty`(정상 401). 묘비가 `refreshTtl` 동안 살아있으므로 그 창 안의 재사용은 3번에서 잡힌다. `null`은 **발급된 적 없거나 `refreshTtl`을 넘긴** 토큰뿐이다.
2. `<userId>`(현행):
   - `grace ≤ 0`(유예 비활성, 기존 안전 스위치) → `DEL`(엄격 단일-사용, today 동작).
   - `grace > 0` → `SET refresh:<oldHash> "grace:<userId>:<now>" EX refreshTtl`.
   - 이어서 새 토큰 `issue(userId)` → `Rotated` 반환.
3. `grace:<userId>:<t0>`(묘비):
   - `now − t0 ≤ grace` → **정상 동시-refresh**: 마커 불변(`t0`·TTL 유지, **연장 금지**), 새 토큰 `issue` → `Rotated`.
   - `now − t0 > grace` → **재사용 감지**:
     - `refresh-reuse-detection = true`(기본) → `revokeAll(userId)` + `WARN` 로그 → `empty`.
     - `= false`(킬스위치) → `empty`만 반환(폐기 없음).

반환 타입·컨트롤러 계약 불변: `Optional<Rotated>`. 재사용이든 만료든 `empty` → `AuthController`가 401.

### 3.3 두 개의 독립 스위치

| 설정 | 타입/기본 | 역할 | "off" 의미 |
|---|---|---|---|
| `devpath.auth.refresh-rotate-grace` | Duration / 30s | 유예/묘비 마커 존재 여부 | `0`이면 회전 즉시 `DEL` = 엄격 단일-사용(today). 묘비 없음 → 재사용 감지도 자연히 무의미(재제시=401). |
| `devpath.auth.refresh-reuse-detection` | boolean / **true** | 유예창 밖 재사용 시 `revokeAll` 실행 여부 | `false`면 스테일 토큰은 여전히 거부(401)하되 **세션 전량 폐기는 안 함** = 공격적 부분만 롤백. |

env override: `DEVPATH_AUTH_REFRESH_REUSE_DETECTION`. gitops 변경 불요(코드 기본값 `true` 사용 — grace와 동일 방식).

킬스위치 `false`의 사용자 체감은 today와 동일하다(스테일 토큰 → 401). 내부 차이는 마커 TTL(refreshTtl vs 30s)뿐이며 무해하다.

### 3.4 `validate` / `revoke` / `revokeAll` / `parseUserId`

- `validate`: 변경 없음(2·3-파트 값 모두에서 userId 파싱). **prod 미사용**(호출부 실측: 테스트 전용, `beta_status`는 별도 `BetaStatusTokens`)이라 영향 없음.
- `revoke` / `revokeAll`: 변경 없음. `DEL`은 묘비에도 그대로 동작. 묘비 해시는 `issue` 시 `refresh:byUser:<userId>` 역인덱스에 이미 등록돼 `revokeAll`로 함께 폐기된다.
- `parseUserId`: **수정 필요.** 현재는 `grace:` 접두 제거 후 **전체**를 `Long.parseLong` → `grace:<id>:<millis>`에서 `NumberFormatException`. → 접두 제거 후 **다음 `:`까지만** 취해 파싱하도록 보강(별도 헬퍼로 `rotatedAt`도 파싱).

### 3.5 로깅 / 관측

- 재사용 감지(enforce) 시 `WARN` 1건: `userId` + "reuse detected, revoking all sessions". **토큰 원문·해시는 로깅 금지.** (`private static final Logger` 필드 추가 — 생성자 시그니처 불변.)
- Micrometer 카운터(`auth.refresh.reuse_detected`)는 **후속** — 1차는 생성자 시그니처(`new RefreshTokenStore(redis, props)`)와 기존 테스트 영향을 피해 **WARN 로그만**. WARN 기반 알림으로 오탐 관찰에 충분.

## 4. 트레이드오프

- **보안 이득**: 유예창(30s) 밖 회전-토큰 재사용 = 탈취 신호 → 전 세션 즉시 무효. `refreshTtl`(14d) 동안의 재사용을 포착(= 스테일 토큰이 악용 가능한 전 기간).
- **오탐 리스크**: `revokeAll`은 그 유저의 **모든 기기 로그아웃**. single-flight(#1) 미착지라 프론트가 유예창 밖에서 스테일 토큰을 재전송하면 오탐 가능. 단, 알려진 이중 refresh(콜백 부트스트랩)는 ms 단위라 30s 유예창 안 → 정상 흡수. 유예창 밖 정상 재사용은 >30s 지연/큐잉이 필요해 드묾. 킬스위치로 즉시 완화.
- **스케일**: 회전마다 묘비 1개가 `refreshTtl`(14d) 생존. 베타 규모(dev 2명)엔 무해. 대규모 시 활성 세션당 수천 키 → 필요 시 묘비 TTL을 `refreshTtl`보다 짧게 튜닝하는 후속 여지(감지 창 ↔ 메모리 trade). 1차 범위 밖.
- **원자성**: 도입 안 함(grace 설계와 동일 계열). 유예/재사용 경계의 동시성 경합은 안전측으로 수렴(둘 다 `revokeAll`=멱등; 경계 순간의 발급-후-폐기=세션 종료). `rotatedAt`은 절대 epoch millis라 멀티 pod에서도 일관.

## 5. 테스트 계획 (Test-First)

기존 `RefreshTokenStoreTest`(`@SpringBootTest` + test 프로필 실 Redis) 패턴 확장. **먼저 실패 확인 후 최소 구현.**

**기존 6개 계약은 반환값 단언이 그대로 유지**(회귀 그린 필수). 특히:
- `graceExpires` / `graceReuseDoesNotExtendGraceTtl`: 유예창 밖 rotate가 여전히 `empty`를 반환 — 단, 이제 부수효과로 `revokeAll`이 돈다(반환 단언은 불변이라 그대로 통과).
- 나머지 4개(`...singleUseWhenGraceDisabled`·`rotateWithinGraceAllowsConcurrentReuse`·`graceTokenStillValidates`·`revokeKillsGraceToken`)도 불변.

**신규 테스트**:

1. `reuseOutsideGraceRevokesAllSessions`: grace=80ms, detection=on. 유저 U에 토큰 `t1`·`t2` 발급(2세션). `t1` rotate→묘비. `sleep(300)`. `t1` 재-rotate → `empty` **그리고** `t2`·직전 신규토큰 모두 무효(`validate` 전부 empty, `refresh:byUser:U` 비었음).
2. `reuseWithinGraceDoesNotRevoke`: grace=30s. `t1` 발급·rotate→묘비. 즉시 `t1` 재-rotate → present(새 토큰), 다른 세션 `t2` 여전히 유효.
3. `reuseOutsideGraceWithDetectionOffDoesNotRevoke`: detection=off, grace=80ms. `t1` rotate, `sleep(300)`, `t1` 재-rotate → `empty`(거부) **하지만** `t2` 여전히 유효(`revokeAll` 미실행).
4. `timestampedMarkerParsesUserId`: `grace:<id>:<millis>` 값에서 `validate`/`rotate`가 올바른 userId 파싱(파서 회귀).

**컨트롤러 통합**(기존 `AuthControllerRefreshUnitTest`/`AuthControllerTest` 계열 패턴 확인 후 동일 방식): 재사용 시 `/auth/refresh` 401 + 후속 요청이 전 세션 폐기됨을 확인.

전체 회귀: `./gradlew test` 그린.

## 6. 릴리스·검증 계획

1. `feat/refresh-reuse-detection` → develop PR (CI 그린 후 머지).
2. develop → main PR(릴리스) → 이미지 빌드 → ArgoCD 반영 확인.
3. **운영 재검증**(런북 진단기법 재사용 — EC2에서 테스트 refresh 토큰 삽입, 종료 시 전량 삭제):
   - ① 유예창 **안** 재사용 = 정상 200·세션 유지.
   - ② 유예창 **밖** 재사용 = 401 + `refresh:byUser:<id>` 전멸 확인.
4. gitops 런북 트러블슈팅/보안 섹션에 원인·수정·검증 기록.

## 7. 후속 백로그 (이 설계 범위 밖)

1. frontend refresh single-flight(#1) — 착지 후 오탐 여지 추가 축소.
2. `BetaGate.admit()` status 정합(#3).
3. Redis 영속성(#4).
4. Micrometer 카운터 · 묘비 TTL 튜닝(대규모 시).
