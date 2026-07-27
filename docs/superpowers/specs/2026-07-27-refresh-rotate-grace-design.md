# Refresh 회전 유예창(Rotation Grace Window) 설계

- 날짜: 2026-07-27
- 상태: 사용자 승인 (구현 전)
- 대상: `devpath-platform-svc` `RefreshTokenStore`
- 관련: gitops `docs/runbook-k3s-bootstrap.md` 🔴 OPEN 섹션(로그인 후 동의 화면 401), documents `handoff-2026-07-27-ws-d-deployed-e2e-open.md`

## 1. 배경 — 증상과 근본 원인

**증상**: 운영(app.leva.ai.kr)에서 로그인 → 가입 동의 화면 진행 불가. 콘솔에 `/auth/refresh` 401, `/dashboard/me` 401.

**근본 원인** (2026-07-27 운영 실측으로 확정):

1. 웹 앱은 OAuth 콜백 착지 시 `/auth/refresh`를 동시 2회 호출한다
   (`AuthController.build()`의 `bootstrapSession()` + 콜백 페이지의 `bootstrapFromCallback()`).
   401 발생 시 `AuthInterceptor`가 refresh를 추가 호출하므로 동시성은 2+α다.
2. 서버 `RefreshTokenStore.rotate()`는 validate→DEL→issue의 **비원자적 단일-사용 회전**이다.
   먼저 처리된 요청이 토큰을 삭제하면, 같은 쿠키를 들고 이미 출발한 두 번째 요청은 반드시 401을 받는다
   (네트워크 왕복 시간 전체가 경쟁 창이므로 브라우저에서는 사실상 상시 발생).
3. 401을 받은 `AuthInterceptor`의 재시도 refresh마저 스테일 쿠키면 실패하고, catch에서 `store.clear()`가
   방금 저장된 access 토큰까지 삭제해 세션이 파괴된다. 동의 제출은 Bearer가 필요하므로 진행 불가.

**운영 실측 증거** (EC2에서 테스트 토큰 삽입 후 실행, 종료 시 전량 삭제·잔여물 없음 확인):

- 순차 호출(회전 체인): `200 → 200` 정상.
- 동일 토큰 동시 2회 × 8라운드: 1라운드 `{200, 401}`(증상 재현), 7라운드 `{200, 200}`(둘 다 DEL 전에
  validate 통과 → **서로 다른 새 토큰 이중 발급**). 8/8 라운드 모두 비원자성 입증.

기각된 가설: PSL로 인한 `Domain=.leva.ai.kr` 쿠키 거부(PSL에 `ai.kr`이 단독 항목으로 존재 →
`leva.ai.kr`은 등록 가능 도메인이라 거부되지 않음. `*.ai.kr` 항목 없음), CORS/credentials(기검증),
재로그인 미수행(사용자 확인), 85행 미승인 분기(사용자 role=ADMIN → `admit()` 무조건 true,
Redis에 `refresh:byUser:1`·`refresh:byUser:2` 존재 = 쿠키 발급 분기 도달 증명).

## 2. 목표 / 비목표

**목표**

- 정상적인 동시 refresh(콜백 이중 부트스트랩, 멀티탭, 인터셉터 재시도)가 401로 세션을 파괴하지 않게 한다.
- 서버 수정만으로 해결한다(웹/모바일 클라이언트 배포 불요, `AuthController` 시그니처 무변경).

**비목표** (후속 백로그로 분리)

- 프론트 refresh single-flight 정리(2차 PR): 불필요한 연속 회전 제거.
- 탈취 토큰 재사용 감지(reuse detection: 유예창 밖 재사용 시 세션 전량 폐기).
- `BetaGate.admit()` ADMIN 조기 return의 status `BETA_PENDING` 방치 수정.
- Redis 영속성(재시작 시 전 세션 소실) 검토.

## 3. 설계

### 3.1 저장 값 포맷

`refresh:<sha256(token)>` 키의 값을 두 형태로 확장한다.

| 값 | 의미 |
|---|---|
| `"<userId>"` | 살아있는(현행) 토큰 — 기존과 동일 |
| `"grace:<userId>"` | 회전으로 대체된 직전 토큰 — 유예창(TTL) 동안만 유효 |

### 3.2 `rotate(oldToken)` 규칙

1. `GET refresh:<oldHash>`:
   - `null` → `Optional.empty()` (기존과 동일 → 컨트롤러 401).
   - `"<userId>"`(현행) → 값을 `"grace:<userId>"`로 교체하고 TTL을 유예창으로 재설정
     (`SET ... EX grace`). 이후 새 토큰 issue.
   - `"grace:<userId>"`(유예) → 유예 마커는 **건드리지 않고**(남은 TTL 유지, 연장 금지) 새 토큰 issue.
2. 반환은 기존과 동일한 `Rotated(userId, newToken)`.

동시에 도착한 두 현행-토큰 rotate가 모두 1번 분기를 타도 결과는 같은 마커 SET(멱등)이며 둘 다 200을
받는다. 원자성(Lua)은 도입하지 않는다 — 단일-사용 엄격성을 의도적으로 유예창으로 완화하는 설계이므로
(Auth0 "Refresh Token Reuse Interval"과 동일 계열 표준 패턴).

### 3.3 `validate(token)` / `revoke(token)` / `revokeAll(userId)`

- `validate`: 두 값 형태 모두에서 userId를 파싱해 반환한다(유예 토큰도 유효로 간주).
- `revoke`/`revokeAll`: 변경 없음 — DEL은 유예 마커에도 그대로 동작한다. 유예 토큰의 해시도
  `refresh:byUser:<userId>` 역인덱스에 이미 있으므로 revokeAll로 함께 폐기된다.

### 3.4 설정

- `AuthProperties`에 `refreshRotateGrace: Duration` 추가, **기본값 30초**
  (`devpath.auth.refresh-rotate-grace`, env `DEVPATH_AUTH_REFRESH_ROTATE_GRACE`).
- `Duration.ZERO`(또는 음수)면 유예 없이 기존과 동일하게 즉시 DEL — 완화 기능을 끄는 안전 스위치.
- gitops 변경 불요(코드 기본값 사용).

### 3.5 트레이드오프 (보안)

- 유예창 30초 동안 직전 refresh 토큰 재사용이 허용된다. 토큰은 HttpOnly+Secure 쿠키로만 운반되고
  Redis에는 해시로만 저장되므로, 창이 짧은 한 수용 가능한 표준 절충이다.
- 유예창 재사용마다 새 토큰이 발급되므로 유예창 내 최대 동시 호출 수만큼 유효 토큰이 존재할 수 있다.
  모두 `byUser` 역인덱스에 등록되어 revokeAll로 일괄 폐기 가능하다.

## 4. 테스트 계획 (Test-First)

기존 `RefreshTokenStoreTest`(`@SpringBootTest` + test 프로필 실 Redis) 패턴을 따른다.

1. **store 단위** (`RefreshTokenStoreTest` 확장, 실패 확인 후 구현):
   - 회전 직후 유예창 내: old 토큰 `rotate()` 재호출 → 성공, userId 동일, 새 토큰은 매회 상이.
   - 유예 토큰 `validate()` → userId 반환.
   - `revoke(old)` 후 유예 재사용 불가.
   - 유예창 만료 후 old 토큰 무효(짧은 grace TTL + 대기로 검증).
   - `refreshRotateGrace=0` → 기존 단일-사용 동작(회전 즉시 old 무효).
2. **컨트롤러 통합** (기존 `AuthControllerTest` 계열 패턴 확인 후 동일 방식):
   - 쿠키 T0로 `/auth/refresh` 200 → **스테일 쿠키 T0로 재호출 → 200** (브라우저 시나리오의 결정적 재현).
3. 기존 전체 테스트 회귀 통과(`./gradlew test`).

기존 테스트 `issueValidateRotateRevoke`의 "회전 후 이전 토큰 무효" 단언은 유예창 도입으로 의미가
바뀌므로, grace=0 스토어로 검증하도록 조정한다(단일-사용 계약 자체는 grace=0 케이스로 계속 보장).

## 5. 릴리스·검증 계획

1. `fix/refresh-rotate-grace` → develop PR (CI 녹색 확인 후 머지).
2. develop → main PR(릴리스) → CI 이미지 빌드 → ArgoCD 반영 확인.
3. **운영 재검증**: ① 실측 스크립트 재실행 — 동시 2회 호출이 `{200, 200}`(401 없음)인지 확인
   ② 사용자 브라우저에서 재로그인 → 동의 화면 진행 → `/dashboard/me` 200 확인.
4. gitops 런북 🔴 OPEN 섹션을 결과로 갱신(원인·수정·검증 기록).

## 6. 후속 백로그 (이 설계 범위 밖)

1. frontend refresh single-flight(2차 PR — bootstrapSession/bootstrapFromCallback/인터셉터 Future 공유).
2. 유예창 밖 재사용 감지 시 세션 전량 폐기(reuse detection).
3. `BetaGate.admit()` ADMIN 경로의 status 미승격 정리.
4. Redis 영속성/재시작 내성 검토(현재 재시작 시 전 세션 로그아웃).
