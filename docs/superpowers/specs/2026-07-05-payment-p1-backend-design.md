# ① 결제 P1 — 백엔드 결제 도메인 (mock 선구현) 설계

- 날짜: 2026-07-05
- 레포: devpath-platform-svc(도메인·API) · devpath-shared(스키마 마이그레이션)
- 상태: 브레인스토밍 승인됨 — **P1 하위 spec**. 상위: [마스터 spec](2026-07-05-payment-portone-design.md).
- 브랜치: `feat/payment-p1-backend`

## 배경 / P1 범위

마스터 spec의 **Phase 분해 중 P1(백엔드 도메인)**. 구독/결제 도메인·스키마·mock PaymentGateway를 선구현해 **가입→구독→PRO 전환→해지** 흐름을 확보한다(H1 지불의사 검증). 실 PortOne·P2 게이팅·프론트는 후속 Phase.

## 확정 결정 (브레인스토밍 2026-07-05)

1. **정기결제 자동 갱신 스케줄러 = P1 범위 밖.** 구독 시작/해지/plan 전이 + `next_billing_at` 필드까지만. 자동 청구 `@Scheduled`는 실 PortOne 단계로 미룬다(mock 자동청구는 가짜 결제만 누적). 갱신 검증은 웹훅/수동 트리거로.
2. **해지 전이 = 잔여기간 유지 + lazy 판정.** cancel은 `CANCELED` 마킹만. plan은 **effective 계산**으로 도출: 활성이거나 `CANCELED && now < next_billing_at`이면 PRO, 그 외 FREE. 스케줄러 없이 조회 시점에 계산하므로 결제한 잔여기간을 존중한다.
3. **참조 패턴**: 조건부 빈은 shared `StorageAutoConfiguration`의 `@ConditionalOnProperty` 패턴을 차용하되, PaymentGateway는 platform **내부 도메인**이므로 `@AutoConfiguration`(shared 자동설정)이 아닌 일반 `@Configuration` + `@ConditionalOnProperty`로 적용한다.

## 스키마 (shared 신규 `V202607051001__payment.sql`)

> 기존 `V*.sql` 관례(식별자·`timestamptz`·`now()` 기본값) 준수. shared 중앙 Flyway, platform은 classpath 로드/validate.

**`subscriptions`**
| 컬럼 | 타입 | 비고 |
|------|------|------|
| id | bigserial PK | |
| user_id | bigint NOT NULL | → users(id) |
| plan | varchar(20) NOT NULL | `PRO` (구독 대상 tier) |
| status | varchar(20) NOT NULL | `ACTIVE`/`CANCELED`/`PAST_DUE` |
| billing_key | varchar(255) NOT NULL | mock 가짜 키 (실 단계 암호화 후속) |
| started_at | timestamptz NOT NULL | |
| next_billing_at | timestamptz NOT NULL | 다음 청구 예정(= 현재 주기 종료) |
| canceled_at | timestamptz NULL | 해지 시각 |
| created_at / updated_at | timestamptz NOT NULL DEFAULT now() | |

- 인덱스: `(user_id)` — 사용자별 활성 구독 조회.
- 활성 구독 유일성: 한 user는 동시 `ACTIVE` 1건(부분 유니크 또는 서비스 레벨 가드 — 구현 시 확정).

**`payments`**
| 컬럼 | 타입 | 비고 |
|------|------|------|
| id | bigserial PK | |
| subscription_id | bigint NOT NULL | → subscriptions(id) |
| amount | integer NOT NULL | KRW 원 단위 정수 |
| currency | varchar(3) NOT NULL DEFAULT 'KRW' | |
| status | varchar(20) NOT NULL | `PAID`/`FAILED` |
| method | varchar(30) NOT NULL | `card`/`tosspay`/`kakaopay`/`naverpay` |
| pg_tx_id | varchar(255) NOT NULL UNIQUE | **웹훅 멱등 키** |
| paid_at | timestamptz NULL | |
| created_at | timestamptz NOT NULL DEFAULT now() | |

- 인덱스: `(subscription_id)`.

**`users`** — 컬럼 추가
- `plan varchar(20) NOT NULL DEFAULT 'FREE'` — **파생 캐시**(SSoT는 `subscriptions`). effective 계산 결과를 subscribe/cancel/webhook/조회 시 반영.

## 도메인 (platform 신규 패키지 `ai.devpath.platform.billing`)

**엔티티**
- `Subscription`(status: `SubscriptionStatus` enum, plan: `Plan` enum) · `Payment`(status: `PaymentStatus` enum).
- `Plan { FREE, PRO }` — user.plan 및 effective plan 공용 enum.

**상수**: `PRICE = 9_900`(월, KRW) — PRO 월 구독료. mock 고정값(이견 시 조정). 실 단계 요금제 테이블은 후속.

**`SubscriptionService`**
- `subscribe(userId, method)`:
  1. 활성 구독 있으면 거부(409).
  2. `gateway.issueBillingKey(userId, method)` → billingKey.
  3. `Subscription` 생성: `ACTIVE`, `started_at=now`, `next_billing_at=now+1개월`.
  4. `gateway.chargeBilling(billingKey, PRICE)` → pgTxId.
  5. `Payment` 생성: `PAID`, `amount=PRICE`, `pg_tx_id=pgTxId`, `method`.
  6. `users.plan=PRO`.
- `cancel(userId)`: 활성 구독 → `status=CANCELED`, `canceled_at=now`. **users.plan은 유지**(잔여기간 effective PRO). 구독 없으면 404.
- `getEffectivePlan(userId)`: `ACTIVE` → PRO; `CANCELED && now < next_billing_at` → PRO; 그 외 → FREE. stale 캐시(만료된 CANCELED인데 users.plan=PRO)면 조회 시 FREE로 **lazy 동기화**.
- `getMine(userId)`: effective plan + 구독 + 결제내역.
- `handleWebhook(event)`: mock 트리거. `pg_tx_id`로 payment 조회 — 있으면 무시(멱등), 없으면 상태 반영/생성.

**포트 `PaymentGateway`**
- `issueBillingKey(userId, method) → String` · `chargeBilling(billingKey, amount) → String(pgTxId)` · `cancelBilling(billingKey)` · `verifyWebhook(payload, signature) → WebhookEvent`.

**`MockPaymentGateway`**(기본 빈)
- UUID 기반 가짜 billingKey/pgTxId, 즉시 성공(`PAID`). `verifyWebhook`은 항상 valid.

**조건부 빈** `PaymentConfig`
- `@Bean @ConditionalOnProperty(name="devpath.payment.provider", havingValue="mock", matchIfMissing=true)` → `MockPaymentGateway`.
- `havingValue="portone"` → `PortOnePaymentGateway`(후속 Phase, P1엔 미구현). 실 키(스토어ID·API키)는 SealedSecret([[sealed-secrets]])로만.

## API

| 메서드/경로 | 요청 | 응답 | 오류 |
|-------------|------|------|------|
| `POST /billing/subscribe` | `{ method }` | `{ plan: "PRO", subscription }` | 409(이미 구독) |
| `POST /billing/cancel` | — | `{ plan(잔여), subscription: {status:CANCELED, effectiveUntil:next_billing_at} }` | 404(구독 없음) |
| `GET /billing/me` | — | `{ plan(effective), subscription\|null, payments[] }` | — |
| `POST /billing/webhook` | mock payload `{ pgTxId, status, ... }` | 200 | (실 단계: 서명 검증 401) |

- 인증: 기존 JWT(SecurityConfig) 기준 `userId` 추출. `/billing/webhook`은 실 단계 서명 검증 전제(mock은 통과).
- 에러 응답: 기존 platform 관례 준수(구현 시 표준 에러 envelope 채택 여부 확인).

## 에러 처리

- 결제 실패(mock 시나리오 트리거) → `payments.FAILED`, 구독 `PAST_DUE`.
- 웹훅 중복(같은 `pg_tx_id`) → 멱등 무시.
- 웹훅 서명 검증 실패 → 401(실 PortOne 단계).

## 테스트 (Test-First)

- `MockPaymentGatewayTest`: 키/txid 발급·즉시성공.
- `SubscriptionServiceTest`(mock PG 주입): subscribe→ACTIVE+PAID+plan PRO / 중복 거부 / cancel→CANCELED+잔여 PRO / effective plan(만료 후 FREE) / webhook 멱등.
- `BillingControllerTest`(@SpringBootTest, provider=mock): 가입→subscribe→GET me(PRO)→cancel→(만료 경계) 전이. `application-test.yml` hikari pool 관례 준수([[devpath-svc-test-context-connection-flake]]).

## 범위 밖 (후속 Phase)

- 자동 갱신 스케줄러 · P2 유료 게이팅(ai-svc plan/quota 연동) · P3 프론트 · 실 PortOne 어댑터 · 환불/부분취소/프로레이션.

## 의존 / 주의

- **shared 발행 게이트**: `V202607051001__payment.sql` 추가 후 platform이 소비하려면 shared 발행 필요. develop 대상은 자동 발행 안 됨 → `gh workflow run publish.yml --ref develop` 수동 발행([[devpath-web-posttier2-roadmap]] C2 교훈).
- **users.plan 캐시 정합**: SSoT는 subscriptions. P2에서 ai-svc가 plan을 소비할 때 stale 위험 → 전파 방식(조회/이벤트)은 P2 하위 spec에서 확정(마스터 spec §P2).
