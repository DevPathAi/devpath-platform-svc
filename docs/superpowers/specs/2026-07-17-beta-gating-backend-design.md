# WS-C1 설계서: 베타 허용리스트 게이팅 — 백엔드

- **작성일**: 2026-07-17
- **워크스트림**: WS-C (베타 허용리스트 게이팅, G2) 중 **C1 = 백엔드**
- **범위 레포**: `devpath-shared`, `devpath-platform-svc`, `devpath-notification-svc`
- **후속(별도 사이클)**: **C2 = 프론트** — `apps/admin` 실API 연동 + 베타 승인 UI, `apps/web` "베타 대기" 페이지. C1 머지 후 별도 brainstorming→spec.

---

## 1. 목표

GitHub/Google OAuth **인증 자체는 통과**하되, **베타 허용리스트에 없는 사용자는 데모 로그인(토큰 발급)을 보류**한다. 관리자가 승인하면 입장 가능해지고, 그 과정에서 3종 이메일 알림이 흐른다. C1은 이 게이팅·승인·알림의 **백엔드 계약**을 curl/테스트로 검증 가능한 수준까지 완성한다(관리자 화면 연동은 C2).

### 배경 (실측, 2026-07-17)

- `OAuth2LoginSuccessHandler`는 `registerOrFind` 후 **무조건 토큰 발급** — 허용리스트/베타 개념 0건.
- platform-svc에 **ADMIN role/authz 없음**(hasRole·@PreAuthorize 0건), `/admin/**` 컨트롤러 없음.
- `JwtService.mintAccessToken(userId, role)`는 이미 `role` 클레임을 발급 → authz 배선이 가볍다.
- notification-svc: `EmailSender`(SMTP) 존재하나 `send(long userId,…)`는 **수신자 이메일 미해결**("수신자 조회 API 후속" 주석). → 베타 이벤트가 **email을 payload에 직접 실어** 이 공백을 우회한다.
- admin 프론트(`apps/admin`)는 존재하나 **mock 전용**이고 `GET /admin/users?status=` 계약을 이미 기대 → C1의 Admin API는 그 계약에 맞춘다.

---

## 2. 데이터 모델 — 허용리스트 ↔ 상태 정합

두 축을 하나의 원천으로 통합한다.

### 2.1 `beta_allowlist` 테이블 (승인된 이메일의 영속 원천)

| 컬럼 | 타입 | 비고 |
|------|------|------|
| `id` | bigserial PK | |
| `email` | varchar UNIQUE NOT NULL | 소문자 정규화 저장 |
| `note` | varchar NULL | 승인 사유/출처(리드폼 등) |
| `added_by` | varchar NULL | 관리자 식별(이메일 또는 userId) |
| `created_at` | timestamptz NOT NULL | |

- **사전승인**: 리드폼에서 승인된 이메일을 사용자가 로그인하기 **전에** 미리 넣을 수 있다.
- Flyway/스키마 마이그레이션은 레포 기존 관례를 따른다(작성 시 실측하여 결정).

### 2.2 `User.status`에 `BETA_PENDING` 추가

- 기존 값: `ACTIVE`(+ 모더레이션용 `WARNED|SUSPENDED|BANNED`가 프론트에 존재). 여기에 `BETA_PENDING` 추가.
- 의미: **로그인은 했으나 미승인**인 사용자의 파생 상태.

### 2.3 게이팅 로직 (`OAuth2LoginSuccessHandler`, `registerOrFind` 직후)

로그인 성공 시, **웹·모바일 분기 이전**에 게이팅을 적용한다(두 플로우 모두 게이팅).

```
allowed = betaAllowlist.contains(normalize(user.email)) || user.status == ACTIVE
if allowed:
    if user.status == BETA_PENDING: user.status = ACTIVE   // 사전승인 이메일로 뒤늦게 로그인한 경우
    → 기존 동선 (토큰 발급 → 웹 /auth/callback | 모바일 딥링크 code)
else:
    boolean firstTime = (user.status != BETA_PENDING)      // 신규 대기자 판정(멱등)
    user.status = BETA_PENDING
    // 토큰 미발급, refresh 쿠키 미설정
    if firstTime: emit BetaWaitlistRegistered(email)       // Outbox
    → response.sendRedirect(webUrl + "/login?beta=pending")
```

- 미승인 사용자는 **토큰을 전혀 받지 못한다**(refresh 쿠키 미발급). 즉 데모 앱 접근 불가.
- `MissingEmailException`(이메일 미반환) 처리는 기존 `/login?error=email_required` 유지 — 게이팅은 이메일이 있는 경우에만 의미.
- 이메일 정규화(소문자/trim)는 허용리스트 조회·저장 양쪽에서 동일 규칙 적용.

---

## 3. 이벤트 & 알림 (3종)

platform이 **Outbox → Kafka**로 발행(기존 `UserRegisteredEvent` 패턴 미러), notification-svc가 소비. payload에 **email 직접 포함**.

### 3.1 신규 이벤트 (`devpath-shared`)

| record | eventType | 필드 |
|--------|-----------|------|
| `BetaWaitlistRegisteredEvent` | `user.beta.waitlisted` | eventId, occurredAt, userId, email |
| `BetaAccessApprovedEvent` | `user.beta.approved` | eventId, occurredAt, userId, email |

- `DomainEvent` 구현, 하위호환 규칙 준수. **직렬화 호환 테스트 먼저 작성**(shared 규칙).

### 3.2 알림 매핑 (notification-svc 컨슈머)

| 이벤트 | 알림 | 수신자 |
|--------|------|--------|
| `user.beta.waitlisted` | ① "대기명단 등록됨" 이메일 | 신청자(event.email) |
| `user.beta.waitlisted` | ② "신규 베타 대기자" 이메일 | 관리자(`devpath.beta.notify-email`) |
| `user.beta.approved` | ③ "이제 입장 가능합니다" 이메일 | 신청자(event.email) |

- `EmailSender`에 **이메일 주소 기반 발송 경로** 추가: `send(String toEmail, String subject, String body)`(기존 `send(long userId,…)`는 수신자 미해결이므로 사용 불가). `SmtpEmailSender`가 `msg.setTo(toEmail)` 채운다.
- Mock 프로파일(`devpath.mail.provider != smtp`)에서는 `MockEmailSender`가 로깅/기록만.
- 각 컨슈머는 **멱등**(역직렬화 실패 skip = 기존 poison 전략, 중복 발송 방지는 베스트에포트).

---

## 4. ADMIN role + Admin API (platform-svc)

이 항목이 admin 백엔드의 **첫 실 슬라이스**다.

### 4.1 role 시딩

- 설정 `devpath.beta.admin-emails`(목록) → `registerOrFind`에서 신규/기존 사용자의 이메일이 목록에 있으면 `role=ADMIN` 부여(기존은 `LEARNER`).
- 별개 설정 `devpath.beta.notify-email`(단일) = §3.2 ②의 관리자 알림 수신 주소.

### 4.2 authz 배선

- `SecurityConfig`에 JWT `role` 클레임 → `ROLE_<role>` authority 변환기(`JwtAuthenticationConverter` + `JwtGrantedAuthoritiesConverter` 커스텀) 추가.
- `authorizeHttpRequests`에 `/admin/**` → `hasRole('ADMIN')`. 비-ADMIN 접근 = 403.

### 4.3 엔드포인트 (admin 프론트가 기대하는 계약 준수)

| 메서드·경로 | 동작 |
|-------------|------|
| `GET /admin/users?status=BETA_PENDING&cursor=` | 상태별 사용자 목록(커서 페이지). 응답=admin `AdminUserRow`(id·nickname·email·role·status) + `Page` 계약 |
| `POST /admin/users/{id}/approve` | 승인: **트랜잭션** — `beta_allowlist`에 이메일 삽입(멱등) + `User.status=ACTIVE` + `BetaAccessApprovedEvent` Outbox 발행 |
| `POST /admin/allowlist {email}` | 로그인 전 사전승인: `beta_allowlist` 삽입(멱등). (해당 User가 이미 BETA_PENDING이면 승인과 동일 처리) |

- `GET /admin/users`는 `status` 미지정 시 전체 반환(admin users 화면 재사용). 페이지네이션은 레포 기존 커서 패턴 실측 후 준수.

---

## 5. 컴포넌트 경계 (단위)

| 단위 | 책임 | 의존 |
|------|------|------|
| `BetaAllowlist` (엔티티/리포지토리) | 승인 이메일 저장·조회 | DB |
| `BetaGate` (도메인 서비스) | allowed 판정 + BETA_PENDING 전이 + 대기 이벤트 발행 | BetaAllowlist, OutboxRepository |
| `OAuth2LoginSuccessHandler` (수정) | 게이팅 호출 후 분기(토큰/대기 리다이렉트) | BetaGate |
| `AdminUserController` + `AdminBetaService` | 목록·승인·사전승인 + 승인 이벤트 | UserRepository, BetaAllowlist, Outbox |
| `RoleAuthoritiesConverter` (SecurityConfig) | JWT role→authority | — |
| notification `BetaNotificationConsumer` | 3종 이벤트→이메일 | EmailSender |
| `EmailSender.send(String,…)` (추가) | 주소 기반 발송 | JavaMailSender |

---

## 6. 테스트 (Test-First)

### shared
- `BetaWaitlistRegisteredEvent`/`BetaAccessApprovedEvent` 직렬화 호환(round-trip) 테스트.

### platform-svc
- **게이팅 분기**: (a) allowlist에 있는 이메일 → ACTIVE·토큰 발급·`/auth/callback`; (b) 없는 신규 → BETA_PENDING·토큰 미발급·`/login?beta=pending`·`BetaWaitlistRegistered` Outbox 1건; (c) 재로그인(이미 BETA_PENDING) → 이벤트 **미중복**.
- **사전승인**: allowlist 선삽입 후 최초 로그인 → 즉시 ACTIVE, 대기 이벤트 없음.
- **승인 API**: `/approve` → status ACTIVE + allowlist 삽입 + `BetaAccessApproved` Outbox. 멱등(2회 호출).
- **authz**: 비-ADMIN JWT로 `/admin/**` 접근 403; ADMIN JWT 200.
- **role 시딩**: `admin-emails`에 포함된 이메일 로그인 시 role=ADMIN.

### notification-svc
- 3종 컨슈머: 각 이벤트→해당 제목/수신자 발송(Mock EmailSender 캡처). 관리자 알림 수신자=`notify-email`. 역직렬화 실패 skip.

### 검증 명령
- platform/shared/notification 각 `./gradlew test`.
- 수동 스모크(선택): bootRun + curl로 `/admin/users`·`/approve`(ADMIN 토큰) 200 확인.

---

## 7. 명시적 비범위 (C1에서 제외)

- **C2 전체**: `apps/admin` mock→실API 전환, 베타 승인 UI(대기자 목록·승인 버튼), `apps/web` "베타 대기" 상태 페이지. C1은 `/login?beta=pending`로 **리다이렉트만** 하고 그 페이지 구현은 C2.
- SMTP 실발송 인프라(운영 메일 계정) 구성 — 배포(WS-D) 소관. C1은 Mock/SMTP 추상화까지.
- 결제·모바일 딥링크 UX 변경(모바일도 게이팅 대상이나 딥링크 대기 화면은 데모 비범위).

---

## 8. 리스크 / 오픈 포인트

- **모바일 플로우**: 게이팅은 웹·모바일 공통 적용하되, 모바일 미승인 시 리다이렉트 UX는 데모 비범위(딥링크 error param 정도로 최소 처리). 구현 시 실측 확정.
- **이메일 정규화 일관성**: 허용리스트 저장/조회, User.email(unique) 규칙과 충돌 없게 소문자/trim 단일 유틸.
- **커서 페이지네이션**: `/admin/users` 응답이 admin 프론트 `Page.fromJson` 계약과 정확히 맞아야 함 — 구현 전 admin `Page` 스키마 실측.
- **Flyway 유무**: platform-svc 마이그레이션 방식(Flyway/ddl-auto) 실측 후 `beta_allowlist`·enum 확장 반영.
