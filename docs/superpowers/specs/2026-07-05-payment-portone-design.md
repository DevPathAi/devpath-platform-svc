# ① 결제 (PortOne 통합 PG · mock 선구현) 설계

- 날짜: 2026-07-05
- 레포: devpath-platform-svc(백엔드 주도) · devpath-shared(스키마) · devpath-frontend(billing)
- 상태: 브레인스토밍 승인됨 — **마스터 spec(작업 구성)**. 각 Phase는 하위 spec→plan.

## 배경 / 목표

MVP 잔여 **① 결제**(로드맵 44). **다중 결제수단(토스페이·카카오페이·네이버페이·카드)**을 지원하고 **구독(정기결제)**으로 유료 tier를 운영한다. 외부(PG 상점 계약·사업자등록)에 게이팅되므로 **mock으로 선구현**하고 실연동만 후속으로 게이팅한다(H1 지불의사 검증 흐름 확보).

## 결정 사항 (브레인스토밍)

- **PG = PortOne(통합)**: 하나의 연동으로 다중 결제수단 + 구독 빌링키(customer_uid 정기결제). 결제수단 추가는 관리자콘솔 설정. (조사: 다중 수단·구독엔 통합 PG가 정석 — 토스 직접은 카카오페이 별도 연동 부담.)
- **실행 깊이 = mock 선구현**: 구독/결제 도메인·스키마·유료 게이팅·프론트 UI를 `MockPaymentGateway`로 선구현. 실 PortOne은 계약 후 어댑터만 교체.
- **tier = FREE / PRO** 2단계(MVP). **게이팅 대상 = AI 멘토 횟수**(기존 quota/killSwitch 표면 연결).
- **아키텍처 = PaymentGateway 포트/어댑터**: shared `ObjectStorage`·`@ConditionalOnProperty` 조건부 빈 패턴 재사용(mock ↔ 실 교체를 코드 변경 없이 설정으로).
- **Phase 분해**: P1 백엔드 도메인 → P2 유료 게이팅 → P3 프론트 → (후속) 실 PortOne.

## 확정 사실 (green field)

- 결제/구독 도메인 **전무**(코드 실측: payment/subscription/billing 매칭은 문서·placeholder뿐). 신규 도메인.
- 참조 패턴: shared `ai.devpath.shared.storage`(포트 `ObjectStorage` + `@ConditionalOnProperty("devpath.storage.endpoint")` 조건부 autoconfig + 소비 svc 런타임 의존).
- 게이팅 표면: ai-svc quota/killSwitch, 프론트 `isQuota`(ApiErrorCode.quotaExceeded).
- 스키마: shared 중앙 Flyway(V…__*.sql), platform은 validate.

## 아키텍처 — PaymentGateway 포트

```
PaymentController → SubscriptionService → PaymentGateway(포트)
                                            ├─ MockPaymentGateway  (선구현: devpath.payment.mock=true 기본)
                                            └─ PortOnePaymentGateway(후속: PortOne V2, customer_uid 빌링키)
```
- 포트 인터페이스: `issueBillingKey(userId, method)`·`chargeBilling(billingKey, amount)`·`cancelBilling(billingKey)`·`verifyWebhook(payload, sig)`.
- 조건부 빈: `devpath.payment.provider=mock|portone`(기본 mock). 실 PortOne 값(스토어ID·API키)은 SealedSecret(배포)으로만.

## 컴포넌트 (Phase별)

### P1. 백엔드 도메인 (platform + shared 스키마)
- **스키마(shared 마이그레이션)**:
  - `subscriptions`(id·user_id·plan·status[ACTIVE/CANCELED/PAST_DUE]·billing_key·started_at·next_billing_at·canceled_at)
  - `payments`(id·subscription_id·amount·currency·status[PAID/FAILED]·method·pg_tx_id·paid_at)
  - `users.plan`(FREE 기본) 컬럼 추가
- **도메인(platform)**: `Subscription`·`Payment` 엔티티, `SubscriptionService`(구독 시작·정기결제·해지·plan 전이), `PaymentGateway` 포트 + `MockPaymentGateway`.
- **API**: `POST /billing/subscribe`(빌링키→구독 생성→plan=PRO), `POST /billing/cancel`(해지→plan=FREE 다음 주기), `GET /billing/me`(구독·내역), `POST /billing/webhook`(mock 트리거).
- **검증**: 가입→결제→해지→plan 전이 @SpringBootTest(mock PG, 실 계약 무관).

### P2. 유료 게이팅
- FREE/PRO plan을 기준으로 **AI 멘토 횟수 등 PRO 제한**. 기존 quota 표면(`quotaExceeded`/`isQuota`)과 연결(무료 한도 초과 → 402/유료 유도).
- plan 조회는 platform이 SSoT, 소비 svc(ai)는 plan/quota 확인(이벤트 or 조회 — 하위 spec에서 확정).

### P3. 프론트 (features/billing)
- `features/billing`(신규): 결제 화면(PortOne 결제위젯 — **mock 모드 즉시 성공**), 무료→유료 전환 모달, 구독 관리(해지)·결제 내역.
- 마이페이지 구독 섹션(P4에서 placeholder였던 자리) 결선.

### (후속) 실 PortOne 연동
- 계약·사업자등록·API키 확보 후 `PortOnePaymentGateway`(PortOne V2 빌링키/결제위젯) 구현 + `devpath.payment.provider=portone` + SealedSecret. 도메인/게이팅/프론트 흐름은 불변.

## 데이터 흐름 (구독 정기결제, mock)

```
[사용자] 결제위젯(mock) → issueBillingKey → SubscriptionService.subscribe
  → subscriptions(ACTIVE, billing_key) + payments(첫 결제 PAID) + users.plan=PRO
  → 다음 주기: 스케줄러 → chargeBilling → payments 추가 (mock 즉시 성공)
  → 해지: cancel → status=CANCELED, 다음 주기부터 plan=FREE
  → 웹훅(mock 수동): verifyWebhook → 결제 상태 반영
```

## 에러 처리

- 결제 실패(mock 시나리오) → payments FAILED, 구독 PAST_DUE, 재시도/유예.
- 무료 한도 초과 → 402/`quotaExceeded` → 프론트 유료 전환 모달.
- 웹훅 서명 검증 실패 → 401(실 PortOne 시).

## 테스트

- P1: SubscriptionService·PaymentGateway(mock) 단위 + 구독 흐름 @SpringBootTest(CI).
- P2: 게이팅(FREE 한도·PRO 무제한) 테스트.
- P3: billing_controller(mock)·화면 스모크(melos).

## 범위 밖

- 실 PortOne 연동(후속 spec), 환불·부분취소·프로레이션, 다중 통화·해외결제, 인보이스·세금계산서.

## 의존

- **② 마이페이지**(구독 섹션 안착, 완료) · **④ 동의**(결제 약관, 완료).
- 배포 시 결제 시크릿은 [[sealed-secrets]] 구조(gitops)로.
