# ① 결제 P1 백엔드 결제 도메인 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** mock PaymentGateway 기반 구독/결제 백엔드 도메인(가입→구독→PRO→해지)을 구현해 지불의사 흐름을 확보한다.

**Architecture:** `PaymentGateway` 포트 + `MockPaymentGateway`(조건부 빈, `devpath.payment.provider=mock` 기본). `subscriptions`/`payments` 스키마 + `users.plan` 컬럼. 해지는 CANCELED 마킹만 하고 plan은 effective 계산(`ACTIVE || (CANCELED && now<next_billing_at) → PRO`, else FREE)으로 lazy 판정.

**Tech Stack:** Spring Boot 4.0.7 · Java 21 · Spring Data JPA · Flyway(shared 중앙 스키마, 테스트만 classpath 적용) · JUnit 5 · Mockito · MockMvc.

## Global Constraints

- **레포/브랜치**: shared 변경은 `devpath-shared`의 `feat/payment-schema`(develop 분기), platform 변경은 `devpath-platform-svc`의 `feat/payment-p1-backend`(이미 분기됨, spec 커밋 존재).
- **shared 발행 게이트**: `V202607051001__payment.sql`가 develop 머지된 뒤 **수동 발행** `gh workflow run publish.yml --ref develop` 해야 platform이 새 스키마를 소비. Task 2·5의 DB 의존 테스트는 이 발행 이후에만 green.
- **요금 상수**: `PRICE = 9_900`(원), `CURRENCY = "KRW"`. PRO 월 구독(주기 = now + 30일).
- **추측 금지**(platform CLAUDE.md): 기존 파일(`SecurityConfig`, `ConsentRevokeConflictException`, 에러 핸들러)을 열어 확인 후 동일 패턴 적용. 모르면 멈추고 확인.
- **TDD**: 각 Task는 실패 테스트 → 최소 구현 → 통과 → 커밋. Conventional Commits + `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- **enum 매핑**: billing 엔티티 상태는 `@Enumerated(EnumType.STRING)` enum. `User.plan`은 기존 User 문자열 컬럼 관례를 따라 `String`(값은 `Plan.name()`).

---

## File Structure

**devpath-shared**
- Create `src/main/resources/db/migration/V202607051001__payment.sql` — subscriptions/payments 테이블 + users.plan 컬럼.

**devpath-platform-svc** — 신규 패키지 `ai.devpath.platform.billing`
- `Plan.java` · `SubscriptionStatus.java` · `PaymentStatus.java` (enum)
- `Subscription.java` · `Payment.java` (JPA 엔티티)
- `SubscriptionRepository.java` · `PaymentRepository.java`
- `PaymentGateway.java` (포트) · `MockPaymentGateway.java` · `PaymentConfig.java` (조건부 빈)
- `SubscriptionConflictException.java` (409)
- `SubscriptionService.java`
- `BillingController.java` + `dto/`(SubscribeRequest, SubscribeResponse, WebhookRequest, SubscriptionView, PaymentView, BillingMeView)
- Modify `user/User.java` (plan 컬럼) · `config/SecurityConfig`(/billing/webhook 공개 — 실경로는 구현 시 확인)

---

## Task 1: shared payment 스키마 마이그레이션 + 발행

**Files:**
- Create: `devpath-shared/src/main/resources/db/migration/V202607051001__payment.sql`

**Interfaces:**
- Produces: `subscriptions`·`payments` 테이블, `users.plan` 컬럼. Task 2 엔티티가 이 스키마에 매핑.

- [ ] **Step 1: shared 브랜치 분기**

```bash
git -C D:/workspace/dpa/devpath-shared fetch origin --quiet
git -C D:/workspace/dpa/devpath-shared checkout develop
git -C D:/workspace/dpa/devpath-shared merge --ff-only origin/develop
git -C D:/workspace/dpa/devpath-shared checkout -b feat/payment-schema
```

- [ ] **Step 2: 마이그레이션 SQL 작성**

Create `V202607051001__payment.sql` (기존 `V202607041001__consent.sql` 스타일: 소문자, bigserial, timestamptz, DEFAULT now()):

```sql
CREATE TABLE subscriptions (
  id bigserial PRIMARY KEY,
  user_id bigint NOT NULL,
  plan varchar(20) NOT NULL,
  status varchar(20) NOT NULL,
  billing_key varchar(255) NOT NULL,
  started_at timestamptz NOT NULL,
  next_billing_at timestamptz NOT NULL,
  canceled_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_subscriptions_user ON subscriptions(user_id);

CREATE TABLE payments (
  id bigserial PRIMARY KEY,
  subscription_id bigint NOT NULL,
  amount integer NOT NULL,
  currency varchar(3) NOT NULL DEFAULT 'KRW',
  status varchar(20) NOT NULL,
  method varchar(30) NOT NULL,
  pg_tx_id varchar(255) NOT NULL UNIQUE,
  paid_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_subscription ON payments(subscription_id);

ALTER TABLE users ADD COLUMN plan varchar(20) NOT NULL DEFAULT 'FREE';
```

- [ ] **Step 3: 로컬 검증 (docker 가용 시)**

Run: `cd D:/workspace/dpa/devpath-shared && ./gradlew build`
Expected: BUILD SUCCESSFUL (shared는 마이그레이션 자체 단위테스트 없음 — consent 선례. 실검증은 Task 5 platform 통합테스트).

- [ ] **Step 4: 커밋 + PR + 머지**

```bash
git -C D:/workspace/dpa/devpath-shared add src/main/resources/db/migration/V202607051001__payment.sql
git -C D:/workspace/dpa/devpath-shared commit -m "feat(schema): 결제 P1 subscriptions/payments + users.plan (V202607051001)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git -C D:/workspace/dpa/devpath-shared push -u origin feat/payment-schema
gh -R DevPathAi/devpath-shared pr create --base develop --head feat/payment-schema --title "feat(schema): 결제 P1 스키마" --body "① 결제 P1 백엔드용 subscriptions/payments 테이블 + users.plan 컬럼."
```
머지는 CI green 확인 후(git-branch-flow). 머지 대기 — 컨트롤러가 확인.

- [ ] **Step 5: 발행 (머지 후)**

```bash
gh -R DevPathAi/devpath-shared workflow run publish.yml --ref develop
```
Expected: 워크플로우 성공 → platform이 새 `0.0.1-SNAPSHOT` 소비 가능. platform은 `./gradlew --refresh-dependencies`로 최신 SNAPSHOT 강제.

> **게이트**: Task 5 통합테스트 실행 전 이 Task가 완료(머지+발행)돼야 한다. Task 2~4는 발행 없이도 착수 가능(2의 JPA 테스트만 발행 대기).

---

## Task 2: 도메인 영속성 — enum · 엔티티 · User.plan · 리포지토리

**Files:**
- Create: `billing/Plan.java` `billing/SubscriptionStatus.java` `billing/PaymentStatus.java` `billing/Subscription.java` `billing/Payment.java` `billing/SubscriptionRepository.java` `billing/PaymentRepository.java`
- Modify: `user/User.java` (plan 필드)
- Test: `billing/SubscriptionPersistenceTest.java`

**Interfaces:**
- Consumes: Task 1 스키마.
- Produces: `Subscription`(getId/userId/plan/status/billingKey/startedAt/nextBillingAt/canceledAt), `Payment`(getId/subscriptionId/amount/currency/status/method/pgTxId/paidAt), enum `Plan{FREE,PRO}`·`SubscriptionStatus{ACTIVE,CANCELED,PAST_DUE}`·`PaymentStatus{PAID,FAILED}`, 리포 파생 쿼리 `findFirstByUserIdAndStatusOrderByStartedAtDesc`·`findFirstByUserIdOrderByStartedAtDesc`·`findBySubscriptionIdOrderByCreatedAtDesc`·`findByPgTxId`.

- [ ] **Step 1: enum 3종 작성**

```java
// billing/Plan.java
package ai.devpath.platform.billing;
public enum Plan { FREE, PRO }
```
```java
// billing/SubscriptionStatus.java
package ai.devpath.platform.billing;
public enum SubscriptionStatus { ACTIVE, CANCELED, PAST_DUE }
```
```java
// billing/PaymentStatus.java
package ai.devpath.platform.billing;
public enum PaymentStatus { PAID, FAILED }
```

- [ ] **Step 2: 엔티티 작성** (User.java 매핑 관례: IDENTITY, `@Column(name=...)`, created_at insertable/updatable=false)

```java
// billing/Subscription.java
package ai.devpath.platform.billing;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "subscriptions")
public class Subscription {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(name = "user_id", nullable = false) private Long userId;
  @Column(nullable = false) private String plan;
  @Enumerated(EnumType.STRING) @Column(nullable = false) private SubscriptionStatus status;
  @Column(name = "billing_key", nullable = false) private String billingKey;
  @Column(name = "started_at", nullable = false) private Instant startedAt;
  @Column(name = "next_billing_at", nullable = false) private Instant nextBillingAt;
  @Column(name = "canceled_at") private Instant canceledAt;
  @Column(name = "created_at", insertable = false, updatable = false) private Instant createdAt;

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public void setUserId(Long v) { this.userId = v; }
  public String getPlan() { return plan; }
  public void setPlan(String v) { this.plan = v; }
  public SubscriptionStatus getStatus() { return status; }
  public void setStatus(SubscriptionStatus v) { this.status = v; }
  public String getBillingKey() { return billingKey; }
  public void setBillingKey(String v) { this.billingKey = v; }
  public Instant getStartedAt() { return startedAt; }
  public void setStartedAt(Instant v) { this.startedAt = v; }
  public Instant getNextBillingAt() { return nextBillingAt; }
  public void setNextBillingAt(Instant v) { this.nextBillingAt = v; }
  public Instant getCanceledAt() { return canceledAt; }
  public void setCanceledAt(Instant v) { this.canceledAt = v; }
}
```
```java
// billing/Payment.java
package ai.devpath.platform.billing;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "payments")
public class Payment {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(name = "subscription_id", nullable = false) private Long subscriptionId;
  @Column(nullable = false) private Integer amount;
  @Column(nullable = false) private String currency;
  @Enumerated(EnumType.STRING) @Column(nullable = false) private PaymentStatus status;
  @Column(nullable = false) private String method;
  @Column(name = "pg_tx_id", nullable = false, unique = true) private String pgTxId;
  @Column(name = "paid_at") private Instant paidAt;
  @Column(name = "created_at", insertable = false, updatable = false) private Instant createdAt;

  public Long getId() { return id; }
  public Long getSubscriptionId() { return subscriptionId; }
  public void setSubscriptionId(Long v) { this.subscriptionId = v; }
  public Integer getAmount() { return amount; }
  public void setAmount(Integer v) { this.amount = v; }
  public String getCurrency() { return currency; }
  public void setCurrency(String v) { this.currency = v; }
  public PaymentStatus getStatus() { return status; }
  public void setStatus(PaymentStatus v) { this.status = v; }
  public String getMethod() { return method; }
  public void setMethod(String v) { this.method = v; }
  public String getPgTxId() { return pgTxId; }
  public void setPgTxId(String v) { this.pgTxId = v; }
  public Instant getPaidAt() { return paidAt; }
  public void setPaidAt(Instant v) { this.paidAt = v; }
  public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 3: User.plan 컬럼 추가** (User.java, consent_status 라인 아래)

`user/User.java`에 필드 + 접근자 추가:
```java
  @Column(nullable = false) private String plan = "FREE";
```
```java
  public String getPlan() { return plan; }
  public void setPlan(String v) { this.plan = v; }
```

- [ ] **Step 4: 리포지토리 작성**

```java
// billing/SubscriptionRepository.java
package ai.devpath.platform.billing;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
  Optional<Subscription> findFirstByUserIdAndStatusOrderByStartedAtDesc(Long userId, SubscriptionStatus status);
  Optional<Subscription> findFirstByUserIdOrderByStartedAtDesc(Long userId);
}
```
```java
// billing/PaymentRepository.java
package ai.devpath.platform.billing;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
  List<Payment> findBySubscriptionIdOrderByCreatedAtDesc(Long subscriptionId);
  Optional<Payment> findByPgTxId(String pgTxId);
}
```

- [ ] **Step 5: 실패 테스트 작성** (`billing/SubscriptionPersistenceTest.java`) — ConsentControllerTest처럼 `@SpringBootTest @ActiveProfiles("test")`로 실제 스키마 검증

```java
package ai.devpath.platform.billing;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SubscriptionPersistenceTest {

  @Autowired SubscriptionRepository subs;
  @Autowired PaymentRepository payments;

  @Test
  void savesAndFindsActiveSubscriptionAndPayment() {
    Subscription s = new Subscription();
    s.setUserId(700001L); s.setPlan("PRO"); s.setStatus(SubscriptionStatus.ACTIVE);
    s.setBillingKey("bkey"); s.setStartedAt(Instant.now());
    s.setNextBillingAt(Instant.now().plus(30, ChronoUnit.DAYS));
    subs.save(s);

    Payment p = new Payment();
    p.setSubscriptionId(s.getId()); p.setAmount(9900); p.setCurrency("KRW");
    p.setStatus(PaymentStatus.PAID); p.setMethod("card"); p.setPgTxId("tx-700001"); p.setPaidAt(Instant.now());
    payments.save(p);

    assertThat(subs.findFirstByUserIdAndStatusOrderByStartedAtDesc(700001L, SubscriptionStatus.ACTIVE)).isPresent();
    assertThat(payments.findByPgTxId("tx-700001")).isPresent();
    assertThat(payments.findBySubscriptionIdOrderByCreatedAtDesc(s.getId())).hasSize(1);
  }
}
```

- [ ] **Step 6: 테스트 실패 확인** (shared 발행 전이면 Flyway validate 실패/스키마 없음으로 실패)

Run: `cd D:/workspace/dpa/devpath-platform-svc && ./gradlew test --tests "*SubscriptionPersistenceTest" --refresh-dependencies`
Expected: FAIL (테이블 없음 or 컴파일 실패) — 구현/발행 전.

- [ ] **Step 7: 테스트 통과 확인** (Task 1 발행 완료 후)

Run: `./gradlew test --tests "*SubscriptionPersistenceTest" --refresh-dependencies`
Expected: PASS.

- [ ] **Step 8: 커밋**

```bash
git -C D:/workspace/dpa/devpath-platform-svc add src/main/java/ai/devpath/platform/billing src/main/java/ai/devpath/platform/user/User.java src/test/java/ai/devpath/platform/billing/SubscriptionPersistenceTest.java
git -C D:/workspace/dpa/devpath-platform-svc commit -m "feat(billing): 구독/결제 엔티티·enum·리포지토리 + users.plan

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: PaymentGateway 포트 + MockPaymentGateway + 조건부 빈

**Files:**
- Create: `billing/PaymentGateway.java` `billing/MockPaymentGateway.java` `billing/PaymentConfig.java`
- Test: `billing/MockPaymentGatewayTest.java`

**Interfaces:**
- Produces: `PaymentGateway` 포트 — `String issueBillingKey(Long userId, String method)` · `String chargeBilling(String billingKey, int amount)`(returns pgTxId) · `void cancelBilling(String billingKey)` · `boolean verifyWebhook(String payload, String signature)`. Task 4가 주입.

- [ ] **Step 1: 포트 인터페이스 작성**

```java
// billing/PaymentGateway.java
package ai.devpath.platform.billing;

public interface PaymentGateway {
  String issueBillingKey(Long userId, String method);
  String chargeBilling(String billingKey, int amount);
  void cancelBilling(String billingKey);
  boolean verifyWebhook(String payload, String signature);
}
```

- [ ] **Step 2: 실패 테스트 작성** (`MockPaymentGatewayTest.java`)

```java
package ai.devpath.platform.billing;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class MockPaymentGatewayTest {

  private final MockPaymentGateway gw = new MockPaymentGateway();

  @Test
  void issuesNonNullBillingKeyAndUniqueTxIds() {
    String key = gw.issueBillingKey(1L, "card");
    assertThat(key).isNotBlank();
    String tx1 = gw.chargeBilling(key, 9900);
    String tx2 = gw.chargeBilling(key, 9900);
    assertThat(tx1).isNotBlank();
    assertThat(tx1).isNotEqualTo(tx2);
    assertThat(gw.verifyWebhook("{}", "sig")).isTrue();
  }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests "*MockPaymentGatewayTest"`
Expected: FAIL (MockPaymentGateway 미존재, 컴파일 실패).

- [ ] **Step 4: MockPaymentGateway + PaymentConfig 구현**

```java
// billing/MockPaymentGateway.java
package ai.devpath.platform.billing;
import java.util.UUID;

/** devpath.payment.provider=mock(기본)일 때 사용. 즉시 성공. */
public class MockPaymentGateway implements PaymentGateway {
  public String issueBillingKey(Long userId, String method) {
    return "mock_bkey_" + userId + "_" + UUID.randomUUID();
  }
  public String chargeBilling(String billingKey, int amount) {
    return "mock_tx_" + UUID.randomUUID();
  }
  public void cancelBilling(String billingKey) { /* no-op */ }
  public boolean verifyWebhook(String payload, String signature) { return true; }
}
```
```java
// billing/PaymentConfig.java
package ai.devpath.platform.billing;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentConfig {
  @Bean
  @ConditionalOnProperty(name = "devpath.payment.provider", havingValue = "mock", matchIfMissing = true)
  public PaymentGateway mockPaymentGateway() {
    return new MockPaymentGateway();
  }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "*MockPaymentGatewayTest"`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git -C D:/workspace/dpa/devpath-platform-svc add src/main/java/ai/devpath/platform/billing/PaymentGateway.java src/main/java/ai/devpath/platform/billing/MockPaymentGateway.java src/main/java/ai/devpath/platform/billing/PaymentConfig.java src/test/java/ai/devpath/platform/billing/MockPaymentGatewayTest.java
git -C D:/workspace/dpa/devpath-platform-svc commit -m "feat(billing): PaymentGateway 포트 + MockPaymentGateway 조건부 빈

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: SubscriptionService

**Files:**
- Create: `billing/SubscriptionConflictException.java` `billing/SubscriptionService.java`
- Test: `billing/SubscriptionServiceTest.java`

**Interfaces:**
- Consumes: SubscriptionRepository·PaymentRepository·UserRepository·PaymentGateway (Task 2·3).
- Produces: `subscribe(long userId, String method) → Plan` · `cancel(long userId)` · `getEffectivePlan(long userId) → Plan` · `getMine(long userId) → BillingMeView`(Task 5에서 DTO 정의; 이 Task는 Plan 계산·구독 로직까지, getMine은 Task 5) · `handleWebhook(String pgTxId, String status)`.

> **먼저 확인**: `ConsentRevokeConflictException.java`를 읽어 409 매핑 방식(어노테이션/상속)을 파악하고 동일 패턴으로 `SubscriptionConflictException` 작성. (추측 금지)

- [ ] **Step 1: 실패 테스트 작성** (`SubscriptionServiceTest.java`, Mockito 단위)

```java
package ai.devpath.platform.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

  @Mock SubscriptionRepository subs;
  @Mock PaymentRepository payments;
  @Mock UserRepository users;
  @Mock PaymentGateway gateway;
  @InjectMocks SubscriptionService service;

  @Test
  void subscribeCreatesActiveSubAndSetsPlanPro() {
    when(subs.findFirstByUserIdAndStatusOrderByStartedAtDesc(1L, SubscriptionStatus.ACTIVE))
        .thenReturn(Optional.empty());
    when(gateway.issueBillingKey(1L, "card")).thenReturn("bkey");
    when(gateway.chargeBilling(eq("bkey"), anyInt())).thenReturn("tx1");
    when(subs.save(any(Subscription.class))).thenAnswer(i -> i.getArgument(0));
    User u = new User();
    when(users.findById(1L)).thenReturn(Optional.of(u));

    Plan plan = service.subscribe(1L, "card");

    assertThat(plan).isEqualTo(Plan.PRO);
    assertThat(u.getPlan()).isEqualTo("PRO");
  }

  @Test
  void subscribeRejectsWhenActiveExists() {
    when(subs.findFirstByUserIdAndStatusOrderByStartedAtDesc(1L, SubscriptionStatus.ACTIVE))
        .thenReturn(Optional.of(new Subscription()));
    assertThatThrownBy(() -> service.subscribe(1L, "card"))
        .isInstanceOf(SubscriptionConflictException.class);
  }

  @Test
  void effectivePlanIsProWhileCanceledButNotExpired() {
    Subscription s = new Subscription();
    s.setStatus(SubscriptionStatus.CANCELED);
    s.setNextBillingAt(Instant.now().plus(5, ChronoUnit.DAYS));
    when(subs.findFirstByUserIdOrderByStartedAtDesc(1L)).thenReturn(Optional.of(s));
    assertThat(service.getEffectivePlan(1L)).isEqualTo(Plan.PRO);
  }

  @Test
  void effectivePlanIsFreeWhenCanceledAndExpired() {
    Subscription s = new Subscription();
    s.setStatus(SubscriptionStatus.CANCELED);
    s.setNextBillingAt(Instant.now().minus(1, ChronoUnit.DAYS));
    when(subs.findFirstByUserIdOrderByStartedAtDesc(1L)).thenReturn(Optional.of(s));
    assertThat(service.getEffectivePlan(1L)).isEqualTo(Plan.FREE);
  }

  @Test
  void webhookIsIdempotentOnDuplicatePgTxId() {
    when(payments.findByPgTxId("tx1")).thenReturn(Optional.of(new Payment()));
    service.handleWebhook("tx1", "PAID"); // 이미 존재 → 아무 저장 없음(예외 없이 반환)
  }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "*SubscriptionServiceTest"`
Expected: FAIL (SubscriptionService·SubscriptionConflictException 미존재).

- [ ] **Step 3: SubscriptionConflictException 작성** (ConsentRevokeConflictException과 동일 패턴)

```java
// billing/SubscriptionConflictException.java  (ConsentRevokeConflictException 확인 후 동일 구조로)
package ai.devpath.platform.billing;

public class SubscriptionConflictException extends RuntimeException {
  public SubscriptionConflictException(String message) { super(message); }
}
```
> 만약 ConsentRevokeConflictException이 `@ResponseStatus`나 특정 베이스 클래스로 409 매핑한다면 그 방식을 그대로 복제할 것.

- [ ] **Step 4: SubscriptionService 구현**

```java
// billing/SubscriptionService.java
package ai.devpath.platform.billing;

import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {

  static final int PRICE = 9_900;
  static final String CURRENCY = "KRW";

  private final SubscriptionRepository subs;
  private final PaymentRepository payments;
  private final UserRepository users;
  private final PaymentGateway gateway;

  public SubscriptionService(SubscriptionRepository subs, PaymentRepository payments,
                             UserRepository users, PaymentGateway gateway) {
    this.subs = subs; this.payments = payments; this.users = users; this.gateway = gateway;
  }

  @Transactional
  public Plan subscribe(long userId, String method) {
    subs.findFirstByUserIdAndStatusOrderByStartedAtDesc(userId, SubscriptionStatus.ACTIVE)
        .ifPresent(s -> { throw new SubscriptionConflictException("이미 활성 구독이 있습니다"); });

    String billingKey = gateway.issueBillingKey(userId, method);
    Instant now = Instant.now();
    Subscription sub = new Subscription();
    sub.setUserId(userId); sub.setPlan("PRO"); sub.setStatus(SubscriptionStatus.ACTIVE);
    sub.setBillingKey(billingKey); sub.setStartedAt(now);
    sub.setNextBillingAt(now.plus(30, ChronoUnit.DAYS));
    sub = subs.save(sub);

    String txId = gateway.chargeBilling(billingKey, PRICE);
    Payment p = new Payment();
    p.setSubscriptionId(sub.getId()); p.setAmount(PRICE); p.setCurrency(CURRENCY);
    p.setStatus(PaymentStatus.PAID); p.setMethod(method); p.setPgTxId(txId); p.setPaidAt(now);
    payments.save(p);

    User u = users.findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));
    u.setPlan("PRO"); users.save(u);
    return Plan.PRO;
  }

  @Transactional
  public void cancel(long userId) {
    Subscription sub = subs.findFirstByUserIdAndStatusOrderByStartedAtDesc(userId, SubscriptionStatus.ACTIVE)
        .orElseThrow(() -> new IllegalArgumentException("활성 구독이 없습니다"));
    sub.setStatus(SubscriptionStatus.CANCELED);
    sub.setCanceledAt(Instant.now());
    subs.save(sub);
    // plan은 유지: 잔여기간 effective PRO. 만료는 getEffectivePlan/getMine에서 lazy 판정.
  }

  @Transactional(readOnly = true)
  public Plan getEffectivePlan(long userId) {
    return subs.findFirstByUserIdOrderByStartedAtDesc(userId)
        .map(this::effectiveOf).orElse(Plan.FREE);
  }

  private Plan effectiveOf(Subscription s) {
    if (s.getStatus() == SubscriptionStatus.ACTIVE) return Plan.PRO;
    if (s.getStatus() == SubscriptionStatus.CANCELED && Instant.now().isBefore(s.getNextBillingAt()))
      return Plan.PRO;
    return Plan.FREE;
  }

  @Transactional
  public void handleWebhook(String pgTxId, String status) {
    if (payments.findByPgTxId(pgTxId).isPresent()) return; // 멱등
    // mock 단계: 미지 pgTxId는 반영할 결제 컨텍스트가 없어 무시(실 PortOne 단계에서 상태 반영).
  }
}
```
> `getMine(...)`은 Task 5에서 DTO와 함께 추가한다(반환 타입 BillingMeView가 Task 5에 정의됨).

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "*SubscriptionServiceTest"`
Expected: PASS (5개 테스트).

- [ ] **Step 6: 커밋**

```bash
git -C D:/workspace/dpa/devpath-platform-svc add src/main/java/ai/devpath/platform/billing/SubscriptionConflictException.java src/main/java/ai/devpath/platform/billing/SubscriptionService.java src/test/java/ai/devpath/platform/billing/SubscriptionServiceTest.java
git -C D:/workspace/dpa/devpath-platform-svc commit -m "feat(billing): SubscriptionService(구독·해지·effective plan·웹훅 멱등)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: BillingController + DTO + 통합 흐름 테스트

**Files:**
- Create: `billing/BillingController.java`, `billing/dto/SubscribeRequest.java` `SubscribeResponse.java` `WebhookRequest.java` `SubscriptionView.java` `PaymentView.java` `BillingMeView.java`
- Modify: `billing/SubscriptionService.java` (getMine 추가), `config/SecurityConfig`(경로는 구현 시 확인 — `/billing/webhook` 공개, `/billing/**` 인증)
- Test: `billing/BillingControllerTest.java`

**Interfaces:**
- Consumes: SubscriptionService (Task 4).
- Produces: REST `/billing/subscribe|cancel|me|webhook`.

> **먼저 확인**: platform `SecurityConfig`(위치는 `Glob **/*SecurityConfig*.java`)를 읽어 기존 authorizeHttpRequests 패턴 파악. `/consents/**`가 인증 요구되는 방식과 동일하게 `/billing/**` 인증 + `/billing/webhook` permitAll 추가.

- [ ] **Step 1: DTO records 작성**

```java
// billing/dto/SubscribeRequest.java
package ai.devpath.platform.billing.dto;
public record SubscribeRequest(String method) {}
```
```java
// billing/dto/SubscribeResponse.java
package ai.devpath.platform.billing.dto;
public record SubscribeResponse(String plan) {}
```
```java
// billing/dto/WebhookRequest.java
package ai.devpath.platform.billing.dto;
public record WebhookRequest(String pgTxId, String status) {}
```
```java
// billing/dto/SubscriptionView.java
package ai.devpath.platform.billing.dto;
import ai.devpath.platform.billing.Subscription;
import java.time.Instant;
public record SubscriptionView(String status, Instant nextBillingAt, Instant canceledAt) {
  public static SubscriptionView of(Subscription s) {
    return new SubscriptionView(s.getStatus().name(), s.getNextBillingAt(), s.getCanceledAt());
  }
}
```
```java
// billing/dto/PaymentView.java
package ai.devpath.platform.billing.dto;
import ai.devpath.platform.billing.Payment;
import java.time.Instant;
public record PaymentView(int amount, String currency, String status, String method, Instant paidAt) {
  public static PaymentView of(Payment p) {
    return new PaymentView(p.getAmount(), p.getCurrency(), p.getStatus().name(), p.getMethod(), p.getPaidAt());
  }
}
```
```java
// billing/dto/BillingMeView.java
package ai.devpath.platform.billing.dto;
import java.util.List;
public record BillingMeView(String plan, SubscriptionView subscription, List<PaymentView> payments) {}
```

- [ ] **Step 2: SubscriptionService.getMine 추가** (Task 4 서비스에 메서드 추가)

```java
// import 추가: ai.devpath.platform.billing.dto.*, java.util.List, java.util.Collections
@Transactional
public BillingMeView getMine(long userId) {
  Plan eff = getEffectivePlan(userId);
  users.findById(userId).ifPresent(u -> {   // lazy 캐시 동기화
    if (!eff.name().equals(u.getPlan())) { u.setPlan(eff.name()); users.save(u); }
  });
  return subs.findFirstByUserIdOrderByStartedAtDesc(userId)
      .map(s -> new BillingMeView(
          eff.name(), SubscriptionView.of(s),
          payments.findBySubscriptionIdOrderByCreatedAtDesc(s.getId()).stream().map(PaymentView::of).toList()))
      .orElse(new BillingMeView(eff.name(), null, List.of()));
}
```
> `getEffectivePlan`은 `@Transactional(readOnly=true)`지만 `getMine`(readOnly=false) 내부 호출 시 같은 트랜잭션에서 실행되어 users.save 가능.

- [ ] **Step 3: 실패 테스트 작성** (`BillingControllerTest.java`, ConsentControllerTest 패턴)

```java
package ai.devpath.platform.billing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.platform.auth.jwt.JwtService;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
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
class BillingControllerTest {

  @Autowired MockMvc mvc;
  @Autowired JwtService jwt;
  @Autowired UserRepository users;

  private User newUser() {
    User u = new User();
    u.setEmail("billing-" + System.nanoTime() + "@example.com");
    u.setNickname("결제유저"); u.setRole("LEARNER"); u.setStatus("ACTIVE"); u.setOnboardingStatus("PENDING");
    return users.save(u);
  }

  @Test
  void subscribeThenMeShowsProAndPayment() throws Exception {
    User u = newUser();
    String token = jwt.mintAccessToken(u.getId(), "LEARNER");

    mvc.perform(post("/billing/subscribe").header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"card\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.plan").value("PRO"));

    mvc.perform(get("/billing/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.plan").value("PRO"))
        .andExpect(jsonPath("$.subscription.status").value("ACTIVE"))
        .andExpect(jsonPath("$.payments[0].amount").value(9900));
  }

  @Test
  void doubleSubscribeIsConflict409() throws Exception {
    User u = newUser();
    String token = jwt.mintAccessToken(u.getId(), "LEARNER");
    mvc.perform(post("/billing/subscribe").header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"card\"}"))
        .andExpect(status().isOk());
    mvc.perform(post("/billing/subscribe").header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"card\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void cancelKeepsProUntilPeriodEnd() throws Exception {
    User u = newUser();
    String token = jwt.mintAccessToken(u.getId(), "LEARNER");
    mvc.perform(post("/billing/subscribe").header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"card\"}"))
        .andExpect(status().isOk());
    mvc.perform(post("/billing/cancel").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    mvc.perform(get("/billing/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.plan").value("PRO"))          // 잔여기간 유지
        .andExpect(jsonPath("$.subscription.status").value("CANCELED"));
  }
}
```

- [ ] **Step 4: 테스트 실패 확인**

Run: `./gradlew test --tests "*BillingControllerTest" --refresh-dependencies`
Expected: FAIL (BillingController 미존재/404).

- [ ] **Step 5: BillingController 작성 + SecurityConfig 수정**

```java
// billing/BillingController.java
package ai.devpath.platform.billing;

import ai.devpath.platform.billing.dto.BillingMeView;
import ai.devpath.platform.billing.dto.SubscribeRequest;
import ai.devpath.platform.billing.dto.SubscribeResponse;
import ai.devpath.platform.billing.dto.WebhookRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/billing")
public class BillingController {

  private final SubscriptionService service;

  public BillingController(SubscriptionService service) { this.service = service; }

  @PostMapping("/subscribe")
  public SubscribeResponse subscribe(@AuthenticationPrincipal Jwt jwt, @RequestBody SubscribeRequest body) {
    return new SubscribeResponse(service.subscribe(Long.parseLong(jwt.getSubject()), body.method()).name());
  }

  @PostMapping("/cancel")
  public void cancel(@AuthenticationPrincipal Jwt jwt) {
    service.cancel(Long.parseLong(jwt.getSubject()));
  }

  @GetMapping("/me")
  public BillingMeView me(@AuthenticationPrincipal Jwt jwt) {
    return service.getMine(Long.parseLong(jwt.getSubject()));
  }

  @PostMapping("/webhook")
  public void webhook(@RequestBody WebhookRequest body) {
    service.handleWebhook(body.pgTxId(), body.status());
  }
}
```
`SecurityConfig`(구현 시 위치 확인): authorizeHttpRequests에 `/billing/webhook` permitAll + `/billing/**` authenticated 추가(기존 `/consents/**` 규칙과 동일 패턴).

- [ ] **Step 6: 테스트 통과 확인** (Task 1 발행 완료 전제)

Run: `./gradlew test --tests "*BillingControllerTest" --refresh-dependencies`
Expected: PASS (3개).

- [ ] **Step 7: 전체 테스트 + 커밋**

Run: `./gradlew test`
Expected: 전체 PASS.
```bash
git -C D:/workspace/dpa/devpath-platform-svc add src/main/java/ai/devpath/platform/billing src/main/java/ai/devpath/platform/config src/test/java/ai/devpath/platform/billing/BillingControllerTest.java
git -C D:/workspace/dpa/devpath-platform-svc commit -m "feat(billing): BillingController + DTO + 구독 흐름 통합 테스트

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review 결과 (작성자 체크)

- **Spec coverage**: 스키마(T1)·도메인/엔티티(T2)·포트/mock/조건부빈(T3)·서비스 subscribe/cancel/effective/webhook(T4)·API 4종+통합(T5) — spec 전 항목 커버. P2 게이팅·스케줄러·실 PortOne은 spec 범위 밖 확인.
- **Type consistency**: `Plan`/`SubscriptionStatus`/`PaymentStatus` enum, `subscribe→Plan`, 리포 파생쿼리 시그니처가 T2 정의와 T4·T5 사용처 일치. `BillingMeView`는 T5에서 정의 후 T4 getMine(T5로 이동)에서 사용 — 순서 정합.
- **미해결 확인 항목**(구현 시 실제 파일 열람 필수, 추측 금지): ① `ConsentRevokeConflictException`의 409 매핑 방식 → `SubscriptionConflictException` 복제. ② `SecurityConfig` 실제 위치·규칙 → `/billing/**`·`/billing/webhook`. ③ shared 발행 후 `--refresh-dependencies`로 SNAPSHOT 갱신.
