# WS-C1 베타 허용리스트 게이팅(백엔드) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OAuth 인증은 통과하되 베타 허용리스트에 없는 사용자는 데모 로그인(토큰 발급)을 보류하고, 관리자 승인 API와 3종 이메일 알림을 갖춘 백엔드를 구현한다.

**Architecture:** platform-svc `OAuth2LoginSuccessHandler`가 `registerOrFind` 직후 `BetaGate`로 게이팅한다. 허용 이메일이면 기존 토큰 동선, 아니면 `User.status=BETA_PENDING`으로 두고 토큰 미발급 + 대기 리다이렉트 + Outbox 이벤트를 발행한다. 관리자는 `/admin/**`(ADMIN role)로 대기자를 조회·승인하고, 승인/대기 이벤트를 notification-svc가 소비해 이메일을 보낸다.

**Tech Stack:** Java 21 · Spring Boot 4.0.7 · Spring Security(oauth2-client/resource-server) · Spring Data JPA · Spring Kafka · Jackson 3(`tools.jackson`) · JUnit 5 · Gradle(Kotlin DSL).

## Global Constraints

- Java 21, Spring Boot 4.0.7. JSON 매퍼는 **Jackson 3**(`tools.jackson.databind.json.JsonMapper`) — `com.fasterxml` 아님.
- 공유 이벤트는 `devpath-shared`의 `ai.devpath.shared.event`에 record + `DomainEvent` 구현. eventType = `<도메인>.<엔티티>.<동작>` 소문자 점표기. **필드는 하위호환(추가만, nullable)**.
- shared 아티팩트는 `ai.devpath:devpath-shared:0.0.1-SNAPSHOT`(GitHub Packages). platform/notification은 이를 소비하므로 **shared 변경은 먼저 develop 머지 후 발행**(`gh workflow run publish.yml --ref develop`)해야 하위 레포가 컴파일된다.
- **런타임 Flyway 없음**(`flyway.enabled=false`). DB 스키마는 shared `src/main/resources/db/migration`(classpath). platform 테스트는 `ddl-auto: validate` + shared 마이그레이션으로 스키마 프로비저닝.
- `User.status`는 plain varchar 컬럼 → `BETA_PENDING` 추가에 **DDL 불필요**(문자열 값).
- 이메일 정규화 = `email.trim().toLowerCase(Locale.ROOT)` 단일 규칙(허용리스트 저장·조회 양쪽).
- notification `EmailSender`의 기존 `send(long userId,…)`는 수신자 미해결 → 베타 알림은 **이벤트 payload의 email**로 발송한다(신규 `send(String toEmail,…)`).
- Git: Conventional Commits. 작업 브랜치는 `develop`에서 분기(레포별 별도 브랜치). `main`·`develop` 직접 커밋 금지.
- 모든 git/파일 명령은 **절대경로 또는 `-C <레포 절대경로>`** 사용(cwd 리셋 방지).

## 실행 순서(레포 의존성)

```
Phase A: devpath-shared (이벤트+마이그레이션)  →  develop 머지 → publish
        │
        ├─ Phase B: devpath-platform-svc (게이팅·ADMIN·API)   [shared 소비]
        └─ Phase C: devpath-notification-svc (이메일 3종)      [shared 소비]
```

- 레포 절대경로:
  - shared = `D:/workspace/dpa/devpath-shared`
  - platform = `D:/workspace/dpa/devpath-platform-svc` (브랜치 `feat/beta-gating-backend` 이미 생성됨)
  - notification = `D:/workspace/dpa/devpath-notification-svc`
- Phase B/C는 Phase A 발행 완료 후 착수. 로컬 선반영이 필요하면 shared에서 `./gradlew publishToMavenLocal` + 소비 레포 `repositories { mavenLocal() }` 임시 추가로 브리지(발행 전 로컬 TDD용). **최종 검증은 develop 발행본 기준**.

---

## Phase A — devpath-shared

브랜치: `D:/workspace/dpa/devpath-shared`에서 `git -C D:/workspace/dpa/devpath-shared checkout develop && git -C D:/workspace/dpa/devpath-shared pull && git -C D:/workspace/dpa/devpath-shared checkout -b feat/beta-gating-events`.

### Task A1: 베타 이벤트 2종 (record + 직렬화 호환 테스트)

**Files:**
- Create: `D:/workspace/dpa/devpath-shared/src/main/java/ai/devpath/shared/event/BetaWaitlistRegisteredEvent.java`
- Create: `D:/workspace/dpa/devpath-shared/src/main/java/ai/devpath/shared/event/BetaAccessApprovedEvent.java`
- Test: `D:/workspace/dpa/devpath-shared/src/test/java/ai/devpath/shared/event/BetaEventSerializationTest.java`

**Interfaces:**
- Consumes: `ai.devpath.shared.event.DomainEvent`(기존), 참조 패턴 = `UserRegisteredEvent`.
- Produces:
  - `BetaWaitlistRegisteredEvent(UUID eventId, Instant occurredAt, long userId, String email)` · `EVENT_TYPE="user.beta.waitlisted"`
  - `BetaAccessApprovedEvent(UUID eventId, Instant occurredAt, long userId, String email)` · `EVENT_TYPE="user.beta.approved"`

- [ ] **Step 1: 실패 테스트 작성** — `BetaEventSerializationTest.java`

```java
package ai.devpath.shared.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BetaEventSerializationTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void waitlistEvent_roundTrips() {
        var e = new BetaWaitlistRegisteredEvent(
                UUID.randomUUID(), Instant.parse("2026-07-17T00:00:00Z"), 42L, "a@b.com");
        String json = mapper.writeValueAsString(e);
        var back = mapper.readValue(json, BetaWaitlistRegisteredEvent.class);
        assertThat(back).isEqualTo(e);
        assertThat(back.eventType()).isEqualTo("user.beta.waitlisted");
    }

    @Test
    void approvedEvent_roundTrips() {
        var e = new BetaAccessApprovedEvent(
                UUID.randomUUID(), Instant.parse("2026-07-17T00:00:00Z"), 42L, "a@b.com");
        String json = mapper.writeValueAsString(e);
        var back = mapper.readValue(json, BetaAccessApprovedEvent.class);
        assertThat(back).isEqualTo(e);
        assertThat(back.eventType()).isEqualTo("user.beta.approved");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-shared test --tests "ai.devpath.shared.event.BetaEventSerializationTest"`
Expected: FAIL — `BetaWaitlistRegisteredEvent` 심볼 없음(컴파일 실패).

- [ ] **Step 3: 이벤트 record 구현**

`BetaWaitlistRegisteredEvent.java`:
```java
package ai.devpath.shared.event;

import java.time.Instant;
import java.util.UUID;

/** 베타 미승인 사용자가 로그인해 대기명단에 오른 이벤트. platform-svc 발행, notification 구독. */
public record BetaWaitlistRegisteredEvent(
        UUID eventId,
        Instant occurredAt,
        long userId,
        String email
) implements DomainEvent {

    public static final String EVENT_TYPE = "user.beta.waitlisted";

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }
}
```

`BetaAccessApprovedEvent.java`: 위와 동일 구조, 클래스명/`EVENT_TYPE="user.beta.approved"`만 변경.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-shared test --tests "ai.devpath.shared.event.BetaEventSerializationTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: 커밋**

```bash
git -C D:/workspace/dpa/devpath-shared add src/main/java/ai/devpath/shared/event/BetaWaitlistRegisteredEvent.java src/main/java/ai/devpath/shared/event/BetaAccessApprovedEvent.java src/test/java/ai/devpath/shared/event/BetaEventSerializationTest.java
git -C D:/workspace/dpa/devpath-shared commit -m "feat: 베타 게이팅 이벤트 2종(user.beta.waitlisted/approved)"
```

### Task A2: `beta_allowlist` 마이그레이션

**Files:**
- Create: `D:/workspace/dpa/devpath-shared/src/main/resources/db/migration/V202607171001__beta_allowlist.sql`

**Interfaces:**
- Produces: 테이블 `beta_allowlist(id, email UNIQUE, note, added_by, created_at)` — Phase B의 `BetaAllowlist` 엔티티가 매핑.

- [ ] **Step 1: 마이그레이션 작성** (기존 `V202607041001__consent.sql` 스타일 준수)

```sql
CREATE TABLE beta_allowlist (
  id         bigserial PRIMARY KEY,
  email      varchar(320) NOT NULL UNIQUE,
  note       varchar(255),
  added_by   varchar(320),
  created_at timestamptz NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: 파일명 규칙 확인** — 버전 `V202607171001`이 기존 최신(`V202607041001`)보다 큰지 육안 확인. 큼(2026-07-17 > 2026-07-04). OK.

- [ ] **Step 3: 커밋**

```bash
git -C D:/workspace/dpa/devpath-shared add src/main/resources/db/migration/V202607171001__beta_allowlist.sql
git -C D:/workspace/dpa/devpath-shared commit -m "feat: beta_allowlist 테이블 마이그레이션"
```

- [ ] **Step 4: shared 빌드 검증**

Run: `./gradlew -p D:/workspace/dpa/devpath-shared build`
Expected: BUILD SUCCESSFUL.

### Task A3: shared 발행 (Phase B/C 게이트)

- [ ] **Step 1:** `feat/beta-gating-events` → `develop` PR 생성, CI 녹색 확인 후 머지(merge commit).

```bash
git -C D:/workspace/dpa/devpath-shared push -u origin feat/beta-gating-events
gh -R DevPathAi/devpath-shared pr create --base develop --head feat/beta-gating-events --title "feat: 베타 게이팅 이벤트+beta_allowlist 마이그레이션" --body "WS-C1 Phase A"
```

- [ ] **Step 2:** 머지 후 SNAPSHOT 발행.

Run: `gh -R DevPathAi/devpath-shared workflow run publish.yml --ref develop`
확인: Actions에서 publish 잡 성공(GitHub Packages `devpath-shared:0.0.1-SNAPSHOT` 갱신).
> 로컬 선반영이 필요하면 대신: `./gradlew -p D:/workspace/dpa/devpath-shared publishToMavenLocal` 후 소비 레포에 `mavenLocal()` 임시 추가.

---

## Phase B — devpath-platform-svc  (브랜치 `feat/beta-gating-backend`, 생성됨)

> Phase A 발행 후 착수. 먼저 `./gradlew -p D:/workspace/dpa/devpath-platform-svc build --refresh-dependencies`로 신규 shared 소비 확인.

### Task B1: `BetaAllowlist` 엔티티 + 리포지토리

**Files:**
- Create: `.../platform/beta/BetaAllowlist.java`
- Create: `.../platform/beta/BetaAllowlistRepository.java`
- Test: `.../platform/beta/BetaAllowlistRepositoryTest.java`
- (기준 경로 `D:/workspace/dpa/devpath-platform-svc/src/main/java/ai/devpath` · 테스트는 `src/test/java/ai/devpath`)

**Interfaces:**
- Produces:
  - `BetaAllowlist`(엔티티, table `beta_allowlist`, 필드 id/email/note/addedBy/createdAt)
  - `BetaAllowlistRepository extends JpaRepository<BetaAllowlist, Long>` — `boolean existsByEmail(String email)`, `Optional<BetaAllowlist> findByEmail(String email)`

- [ ] **Step 1: 실패 테스트 작성** — `BetaAllowlistRepositoryTest.java` (`@DataJpaTest` 슬라이스)

```java
package ai.devpath.platform.beta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class BetaAllowlistRepositoryTest {

    @Autowired BetaAllowlistRepository repo;

    @Test
    void existsByEmail_reflectsSavedRows() {
        BetaAllowlist row = new BetaAllowlist();
        row.setEmail("beta@devpath.ai");
        repo.save(row);
        assertThat(repo.existsByEmail("beta@devpath.ai")).isTrue();
        assertThat(repo.existsByEmail("nope@devpath.ai")).isFalse();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-platform-svc test --tests "ai.devpath.platform.beta.BetaAllowlistRepositoryTest"`
Expected: FAIL — `BetaAllowlist` 심볼 없음.

- [ ] **Step 3: 엔티티/리포지토리 구현**

`BetaAllowlist.java`:
```java
package ai.devpath.platform.beta;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "beta_allowlist")
public class BetaAllowlist {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true) private String email;
    private String note;
    @Column(name = "added_by") private String addedBy;
    @Column(name = "created_at", insertable = false, updatable = false) private Instant createdAt;

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public String getAddedBy() { return addedBy; }
    public void setAddedBy(String v) { this.addedBy = v; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`BetaAllowlistRepository.java`:
```java
package ai.devpath.platform.beta;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BetaAllowlistRepository extends JpaRepository<BetaAllowlist, Long> {
    boolean existsByEmail(String email);
    Optional<BetaAllowlist> findByEmail(String email);
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-platform-svc test --tests "ai.devpath.platform.beta.BetaAllowlistRepositoryTest"`
Expected: PASS.
> 실패 시(스키마 validate 오류) shared 발행본에 `beta_allowlist` 포함됐는지 확인(Task A3).

- [ ] **Step 5: 커밋**

```bash
git -C D:/workspace/dpa/devpath-platform-svc add src/main/java/ai/devpath/platform/beta/BetaAllowlist.java src/main/java/ai/devpath/platform/beta/BetaAllowlistRepository.java src/test/java/ai/devpath/platform/beta/BetaAllowlistRepositoryTest.java
git -C D:/workspace/dpa/devpath-platform-svc commit -m "feat: beta_allowlist 엔티티/리포지토리"
```

### Task B2: `BetaProperties` (admin-emails / notify-email)

**Files:**
- Create: `.../platform/config/BetaProperties.java`
- Modify: `.../src/main/resources/application.yml` (기본값 배선)

**Interfaces:**
- Produces: `BetaProperties` — `List<String> getAdminEmails()`, `String getNotifyEmail()`, `String getBetaPendingRedirect()`(기본 `"/login?beta=pending"`). `@ConfigurationProperties("devpath.beta")` (PlatformApplication의 `@ConfigurationPropertiesScan`이 자동 등록).

- [ ] **Step 1: 구현** — `BetaProperties.java`

```java
package ai.devpath.platform.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("devpath.beta")
public class BetaProperties {
    private List<String> adminEmails = List.of();
    private String notifyEmail;
    private String pendingRedirect = "/login?beta=pending";

    public List<String> getAdminEmails() { return adminEmails; }
    public void setAdminEmails(List<String> v) { this.adminEmails = v; }
    public String getNotifyEmail() { return notifyEmail; }
    public void setNotifyEmail(String v) { this.notifyEmail = v; }
    public String getPendingRedirect() { return pendingRedirect; }
    public void setPendingRedirect(String v) { this.pendingRedirect = v; }
}
```

- [ ] **Step 2: application.yml에 기본값 추가** (기존 `devpath:` 트리 하위)

```yaml
devpath:
  beta:
    admin-emails: ${BETA_ADMIN_EMAILS:}      # 콤마 구분, ADMIN role 부여 대상
    notify-email: ${BETA_NOTIFY_EMAIL:}      # 신규 대기자 알림 수신 관리자 주소
    pending-redirect: ${BETA_PENDING_REDIRECT:/login?beta=pending}
```
> 기존 `application.yml`의 `devpath:` 매핑 위치를 실측해 그 하위에 병합한다(중복 키 생성 금지).

- [ ] **Step 3: 부팅 검증(정적)**

Run: `./gradlew -p D:/workspace/dpa/devpath-platform-svc compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 커밋**

```bash
git -C D:/workspace/dpa/devpath-platform-svc add src/main/java/ai/devpath/platform/config/BetaProperties.java src/main/resources/application.yml
git -C D:/workspace/dpa/devpath-platform-svc commit -m "feat: BetaProperties(admin-emails/notify-email/pending-redirect)"
```

### Task B3: `BetaGate` 도메인 서비스 (게이팅 판정 + 대기 전이·이벤트)

**Files:**
- Create: `.../platform/beta/BetaGate.java`
- Test: `.../platform/beta/BetaGateTest.java`

**Interfaces:**
- Consumes: `BetaAllowlistRepository`(B1), `UserRepository`(기존, `save`), `OutboxRepository`(기존), `JsonMapper`(Jackson3), `BetaWaitlistRegisteredEvent`(A1).
- Produces: `BetaGate`
  - `boolean admit(User user)` — 반환 true=입장 허용(호출부가 토큰 발급). 내부 부수효과: 미허용 시 `user.status=BETA_PENDING` 저장 + (최초 전이일 때만) 대기 이벤트 Outbox 기록; 허용이며 기존 status가 BETA_PENDING이면 ACTIVE로 승격 저장.
  - `static String normalize(String email)` — `email.trim().toLowerCase(Locale.ROOT)`.

- [ ] **Step 1: 실패 테스트 작성** — `BetaGateTest.java` (Mockito 단위 테스트)

```java
package ai.devpath.platform.beta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BetaGateTest {

    private final BetaAllowlistRepository allow = mock(BetaAllowlistRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final BetaGate gate = new BetaGate(allow, users, outbox, JsonMapper.builder().build());

    private User user(String email, String status) {
        User u = new User();
        u.setEmail(email);
        u.setStatus(status);
        return u;
    }

    @Test
    void allowlistedUser_isAdmitted_noWaitlistEvent() {
        when(allow.existsByEmail("a@b.com")).thenReturn(true);
        assertThat(gate.admit(user("A@b.com", "ACTIVE"))).isTrue();
        verify(outbox, never()).save(any());
    }

    @Test
    void unlistedNewUser_isHeld_setsPending_andEmitsEvent() {
        when(allow.existsByEmail("x@y.com")).thenReturn(false);
        User u = user("x@y.com", "ACTIVE");
        assertThat(gate.admit(u)).isFalse();
        assertThat(u.getStatus()).isEqualTo("BETA_PENDING");
        verify(users).save(u);
        verify(outbox, times(1)).save(any(OutboxEntry.class));
    }

    @Test
    void alreadyPendingUser_reLogin_emitsNoDuplicateEvent() {
        when(allow.existsByEmail("x@y.com")).thenReturn(false);
        User u = user("x@y.com", "BETA_PENDING");
        assertThat(gate.admit(u)).isFalse();
        verify(outbox, never()).save(any());
    }

    @Test
    void preApprovedPendingUser_isPromotedToActive() {
        when(allow.existsByEmail("x@y.com")).thenReturn(true);
        User u = user("x@y.com", "BETA_PENDING");
        assertThat(gate.admit(u)).isTrue();
        assertThat(u.getStatus()).isEqualTo("ACTIVE");
        verify(users).save(u);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-platform-svc test --tests "ai.devpath.platform.beta.BetaGateTest"`
Expected: FAIL — `BetaGate` 심볼 없음.

- [ ] **Step 3: 구현** — `BetaGate.java` (이벤트 직렬화·Outbox 기록은 `UserRegistrationService.writeOutbox` 패턴 미러)

```java
package ai.devpath.platform.beta;

import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import ai.devpath.shared.event.BetaWaitlistRegisteredEvent;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class BetaGate {

    static final String PENDING = "BETA_PENDING";
    static final String ACTIVE = "ACTIVE";

    private final BetaAllowlistRepository allowlist;
    private final UserRepository users;
    private final OutboxRepository outbox;
    private final JsonMapper jsonMapper;

    public BetaGate(BetaAllowlistRepository allowlist, UserRepository users,
            OutboxRepository outbox, JsonMapper jsonMapper) {
        this.allowlist = allowlist;
        this.users = users;
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public boolean admit(User user) {
        boolean allowed = allowlist.existsByEmail(normalize(user.getEmail()));
        if (allowed) {
            if (PENDING.equals(user.getStatus())) {
                user.setStatus(ACTIVE);
                users.save(user);
            }
            return true;
        }
        boolean firstTime = !PENDING.equals(user.getStatus());
        if (firstTime) {
            user.setStatus(PENDING);
            users.save(user);
            writeWaitlistOutbox(user);
        }
        return false;
    }

    private void writeWaitlistOutbox(User user) {
        var event = new BetaWaitlistRegisteredEvent(
                UUID.randomUUID(), Instant.now(), user.getId(), normalize(user.getEmail()));
        OutboxEntry entry = new OutboxEntry();
        entry.setAggregateType("user");
        entry.setAggregateId(String.valueOf(user.getId()));
        entry.setEventType(BetaWaitlistRegisteredEvent.EVENT_TYPE);
        entry.setPayload(serialize(event));
        entry.setCreatedAt(Instant.now());
        outbox.save(entry);
    }

    private String serialize(Object event) {
        try {
            return jsonMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("Beta 이벤트 직렬화 실패", e);
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-platform-svc test --tests "ai.devpath.platform.beta.BetaGateTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: 커밋**

```bash
git -C D:/workspace/dpa/devpath-platform-svc add src/main/java/ai/devpath/platform/beta/BetaGate.java src/test/java/ai/devpath/platform/beta/BetaGateTest.java
git -C D:/workspace/dpa/devpath-platform-svc commit -m "feat: BetaGate 게이팅 판정+대기 전이/이벤트"
```

### Task B4: `OAuth2LoginSuccessHandler`에 게이팅 배선

**Files:**
- Modify: `.../platform/auth/OAuth2LoginSuccessHandler.java`
- Test: `.../platform/auth/OAuth2LoginSuccessHandlerBetaTest.java`

**Interfaces:**
- Consumes: `BetaGate.admit(User)`(B3), `BetaProperties.getPendingRedirect()`(B2).
- 게이팅은 **모바일/웹 분기 이전**에 적용: `admit`가 false면 토큰·쿠키 없이 `webUrl + pendingRedirect`로 리다이렉트하고 종료.

- [ ] **Step 1: 실패 테스트 작성** — `OAuth2LoginSuccessHandlerBetaTest.java`

기존 `OAuth2LoginSuccessHandlerTest.java`의 셋업(Mock 토큰/Authentication 구성)을 참조해 동일 패턴으로 작성한다. 핵심 단언 2가지:

```java
// (a) 미승인: admit=false → refresh 쿠키 미설정 + pendingRedirect로 리다이렉트
@Test
void unlistedUser_redirectsToPending_noRefreshCookie() throws Exception {
    when(betaGate.admit(any())).thenReturn(false);
    when(betaProps.getPendingRedirect()).thenReturn("/login?beta=pending");
    when(props.getWebUrl()).thenReturn("https://app.example");
    // ... registration.registerOrFind(...) → user stub, token/authentication stub (기존 테스트 셋업 재사용)

    handler.onAuthenticationSuccess(request, response, authentication);

    verify(response).sendRedirect("https://app.example/login?beta=pending");
    verify(response, never()).addHeader(eq(HttpHeaders.SET_COOKIE), anyString());
    verifyNoInteractions(refreshStore);
}

// (b) 승인: admit=true → 기존 동선(refresh 쿠키 + /auth/callback)
@Test
void admittedUser_followsExistingWebFlow() throws Exception {
    when(betaGate.admit(any())).thenReturn(true);
    when(refreshStore.issue(anyLong())).thenReturn("rt");
    // ... 기존 셋업

    handler.onAuthenticationSuccess(request, response, authentication);

    verify(response).sendRedirect("https://app.example/auth/callback");
    verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), anyString());
}
```
> 정확한 Mock 구성(OAuth2AuthenticationToken/attributes/authorizedClients)은 `OAuth2LoginSuccessHandlerTest.java`를 그대로 따른다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-platform-svc test --tests "ai.devpath.platform.auth.OAuth2LoginSuccessHandlerBetaTest"`
Expected: FAIL — 생성자에 `BetaGate`/`BetaProperties` 미주입(컴파일 실패) 또는 리다이렉트 불일치.

- [ ] **Step 3: 핸들러 수정** — 생성자에 `BetaGate`, `BetaProperties` 추가하고, `registerOrFind` 직후(모바일 state 분기 **이전**) 게이팅 삽입:

```java
// registration.registerOrFind(...) 로 user 확보 직후:
if (!betaGate.admit(user)) {
    response.sendRedirect(props.getWebUrl() + betaProps.getPendingRedirect());
    return;
}
// 이하 기존 모바일 state 분기 / 웹 refresh 발급 로직 유지
```
생성자 필드 `private final BetaGate betaGate; private final BetaProperties betaProps;` 추가 및 주입.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-platform-svc test --tests "ai.devpath.platform.auth.OAuth2LoginSuccessHandler*"`
Expected: PASS (기존 + 신규 베타 테스트 모두).

- [ ] **Step 5: 커밋**

```bash
git -C D:/workspace/dpa/devpath-platform-svc add src/main/java/ai/devpath/platform/auth/OAuth2LoginSuccessHandler.java src/test/java/ai/devpath/platform/auth/OAuth2LoginSuccessHandlerBetaTest.java
git -C D:/workspace/dpa/devpath-platform-svc commit -m "feat: OAuth 로그인 성공 시 베타 게이팅 적용"
```

### Task B5: ADMIN role 시딩 (`UserRegistrationService`)

**Files:**
- Modify: `.../platform/auth/UserRegistrationService.java`
- Test: `.../platform/auth/UserRegistrationServiceAdminRoleTest.java`

**Interfaces:**
- Consumes: `BetaProperties.getAdminEmails()`(B2), `BetaGate.normalize`(B3).
- 신규 User 생성 시 `normalize(email) ∈ adminEmails`면 `role="ADMIN"`, 아니면 기존 `"LEARNER"`.

- [ ] **Step 1: 실패 테스트 작성**

```java
package ai.devpath.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ai.devpath.platform.beta.BetaGate; // normalize 참조용(선택)
import ai.devpath.platform.config.BetaProperties;
import ai.devpath.platform.user.*;
// ... 기존 UserRegistrationService 협력자 mock

class UserRegistrationServiceAdminRoleTest {
    // adminEmails=["admin@devpath.ai"] 주입 시, 신규 가입 이메일이 admin이면 role=ADMIN
    @Test
    void newUserWithAdminEmail_getsAdminRole() {
        // users.findByEmail → empty, identities.findБy... → empty
        // registerOrFind(new OauthUser("GOOGLE","gid","Admin@devpath.ai","nick",null))
        // captured saved User.role == "ADMIN"
    }
    @Test
    void newUserWithNormalEmail_getsLearnerRole() { /* role == "LEARNER" */ }
}
```
> 협력자 mock 구성은 기존 `UserRegistrationService` 테스트가 있으면 그 셋업을 재사용, 없으면 `UserRepository`/`UserOauthIdentityRepository`/`UserProfileRepository`/`OutboxRepository`/`TokenCipher`/`JsonMapper`를 mock하고 `users.save`를 `ArgumentCaptor<User>`로 포획해 role 검증.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-platform-svc test --tests "ai.devpath.platform.auth.UserRegistrationServiceAdminRoleTest"`
Expected: FAIL — 생성자에 `BetaProperties` 미주입 또는 role이 항상 LEARNER.

- [ ] **Step 3: 구현** — 생성자에 `BetaProperties betaProps` 추가, 신규 User 분기에서:

```java
user.setRole(isAdmin(oauth.email()) ? "ADMIN" : "LEARNER");
// ...
private boolean isAdmin(String email) {
    String n = BetaGate.normalize(email);
    return n != null && betaProps.getAdminEmails().stream()
            .map(BetaGate::normalize).anyMatch(n::equals);
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-platform-svc test --tests "ai.devpath.platform.auth.UserRegistrationService*"`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git -C D:/workspace/dpa/devpath-platform-svc add -A && git -C D:/workspace/dpa/devpath-platform-svc commit -m "feat: admin-emails 기반 ADMIN role 시딩"
```

### Task B6: JWT role→authority 변환 + `/admin/**` authz

**Files:**
- Modify: `.../platform/config/SecurityConfig.java`
- Test: `.../platform/config/AdminAuthzTest.java`

**Interfaces:**
- JWT `role` 클레임(예 `"ADMIN"`) → `ROLE_ADMIN` authority. `/admin/**`는 `hasRole("ADMIN")`. 그 외 기존 `.anyRequest().authenticated()` 유지.

- [ ] **Step 1: 실패 테스트 작성** — `AdminAuthzTest.java` (`@WebMvcTest` 또는 `@SpringBootTest`+MockMvc; 기존 테스트 스타일 실측 후 택1)

```java
// 비-ADMIN(role=LEARNER) JWT로 GET /admin/users → 403
// ADMIN(role=ADMIN) JWT로 GET /admin/users → 200(또는 컨트롤러 부재 시 404가 아닌 인가통과 확인)
```
> 임시 스텁 엔드포인트 없이 검증하려면 B8(AdminUserController) 이후로 이 테스트의 200 케이스를 미뤄도 된다. 최소한 **403 케이스**(비-ADMIN 차단)를 먼저 통과시킨다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-platform-svc test --tests "ai.devpath.platform.config.AdminAuthzTest"`
Expected: FAIL — `/admin/**` 규칙 없어 401/403 기대와 불일치.

- [ ] **Step 3: SecurityConfig 수정**

```java
// authorizeHttpRequests 체인에 추가(permitAll 뒤, anyRequest 앞):
.requestMatchers("/admin/**").hasRole("ADMIN")

// resource server에 role 클레임 변환기 배선:
.oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(adminRoleConverter())));

// 변환기 빈:
private JwtAuthenticationConverter adminRoleConverter() {
    JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
    conv.setJwtGrantedAuthoritiesConverter(jwt -> {
        String role = jwt.getClaimAsString("role");
        return role == null ? java.util.List.of()
                : java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role));
    });
    return conv;
}
```
필요 import: `org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter`, `org.springframework.security.core.authority.SimpleGrantedAuthority`.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-platform-svc test --tests "ai.devpath.platform.config.AdminAuthzTest"`
Expected: PASS(최소 403 케이스).

- [ ] **Step 5: 커밋**

```bash
git -C D:/workspace/dpa/devpath-platform-svc add -A && git -C D:/workspace/dpa/devpath-platform-svc commit -m "feat: JWT role→ROLE_ 변환 + /admin/** ADMIN 인가"
```

### Task B7: `AdminBetaService` (승인·사전승인 트랜잭션 + 승인 이벤트)

**Files:**
- Create: `.../platform/beta/AdminBetaService.java`
- Test: `.../platform/beta/AdminBetaServiceTest.java`

**Interfaces:**
- Consumes: `UserRepository`, `BetaAllowlistRepository`, `OutboxRepository`, `JsonMapper`, `BetaAccessApprovedEvent`(A1).
- Produces: `AdminBetaService`
  - `void approveUser(long userId)` — User 조회 → `beta_allowlist` upsert(email, 멱등) + `status=ACTIVE` + `BetaAccessApprovedEvent` Outbox.
  - `void preApprove(String email, String addedBy)` — `beta_allowlist` upsert(멱등). 해당 email의 기존 BETA_PENDING User가 있으면 approveUser 동일 처리.

- [ ] **Step 1: 실패 테스트 작성**

```java
// approveUser: allowlist.save 1회 + user.status=ACTIVE + outbox.save(BetaAccessApproved) 1회
// approveUser 2회(멱등): allowlist가 이미 있으면 중복 insert 안 함(existsByEmail 가드), 이벤트는 승인마다 발행 or 상태가 이미 ACTIVE면 미발행 — 아래 규칙 채택:
//   status가 ACTIVE로 바뀌는 전이일 때만 이벤트 발행(재승인 무발행).
// preApprove: allowlist 없으면 save, 있으면 무시.
```
채택 규칙(테스트로 고정): **`approveUser`는 status가 ACTIVE가 아닐 때만** allowlist 추가·이벤트 발행(멱등·무중복). 이미 ACTIVE면 no-op.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-platform-svc test --tests "ai.devpath.platform.beta.AdminBetaServiceTest"`
Expected: FAIL — `AdminBetaService` 심볼 없음.

- [ ] **Step 3: 구현**

```java
package ai.devpath.platform.beta;

import ai.devpath.platform.outbox.OutboxEntry;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import ai.devpath.shared.event.BetaAccessApprovedEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AdminBetaService {

    private final UserRepository users;
    private final BetaAllowlistRepository allowlist;
    private final OutboxRepository outbox;
    private final JsonMapper jsonMapper;

    public AdminBetaService(UserRepository users, BetaAllowlistRepository allowlist,
            OutboxRepository outbox, JsonMapper jsonMapper) {
        this.users = users;
        this.allowlist = allowlist;
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public void approveUser(long userId) {
        User user = users.findById(userId).orElseThrow(
                () -> new IllegalArgumentException("user not found: " + userId));
        if ("ACTIVE".equals(user.getStatus())) return; // 멱등: 이미 활성
        String email = BetaGate.normalize(user.getEmail());
        if (!allowlist.existsByEmail(email)) {
            BetaAllowlist row = new BetaAllowlist();
            row.setEmail(email);
            row.setNote("approved via admin");
            allowlist.save(row);
        }
        user.setStatus("ACTIVE");
        users.save(user);
        writeApprovedOutbox(user, email);
    }

    @Transactional
    public void preApprove(String rawEmail, String addedBy) {
        String email = BetaGate.normalize(rawEmail);
        if (!allowlist.existsByEmail(email)) {
            BetaAllowlist row = new BetaAllowlist();
            row.setEmail(email);
            row.setAddedBy(addedBy);
            allowlist.save(row);
        }
        users.findByEmail(email)
                .filter(u -> !"ACTIVE".equals(u.getStatus()))
                .ifPresent(u -> approveUser(u.getId()));
    }

    private void writeApprovedOutbox(User user, String email) {
        var event = new BetaAccessApprovedEvent(UUID.randomUUID(), Instant.now(), user.getId(), email);
        OutboxEntry entry = new OutboxEntry();
        entry.setAggregateType("user");
        entry.setAggregateId(String.valueOf(user.getId()));
        entry.setEventType(BetaAccessApprovedEvent.EVENT_TYPE);
        entry.setPayload(serialize(event));
        entry.setCreatedAt(Instant.now());
        outbox.save(entry);
    }

    private String serialize(Object event) {
        try { return jsonMapper.writeValueAsString(event); }
        catch (Exception e) { throw new IllegalStateException("Beta 승인 이벤트 직렬화 실패", e); }
    }
}
```
> `preApprove`의 `approveUser` 재진입은 이미 allowlist에 넣었으므로 중복 insert 없음(existsByEmail 가드). `@Transactional` 자가호출 주의: 같은 빈 내부 호출이라 프록시 미적용이나, 단일 트랜잭션으로 묶여 동작엔 문제 없음(둘 다 write, 예외 시 전체 롤백). 테스트로 동작 고정.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-platform-svc test --tests "ai.devpath.platform.beta.AdminBetaServiceTest"`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git -C D:/workspace/dpa/devpath-platform-svc add src/main/java/ai/devpath/platform/beta/AdminBetaService.java src/test/java/ai/devpath/platform/beta/AdminBetaServiceTest.java
git -C D:/workspace/dpa/devpath-platform-svc commit -m "feat: AdminBetaService 승인/사전승인+승인 이벤트"
```

### Task B8: `AdminUserController` (목록·승인·사전승인)

**Files:**
- Create: `.../platform/beta/AdminUserController.java`
- Create: `.../platform/beta/dto/AdminUserRow.java` (응답 DTO)
- Test: `.../platform/beta/AdminUserControllerTest.java` (MockMvc)
- Modify(선택): `UserRepository`에 상태별 조회 메서드 추가.

**Interfaces:**
- 응답 계약(admin 프론트 `Page.fromJson` 준수): `{ "data": [ {id,nickname,email,role,status} ], "nextCursor": string|null, "limit": int }`.
- 엔드포인트:
  - `GET /admin/users?status=BETA_PENDING&cursor=&limit=` — 상태 필터(없으면 전체), 커서 페이지.
  - `POST /admin/users/{id}/approve` → 204. `AdminBetaService.approveUser`.
  - `POST /admin/allowlist` body `{"email": "..."}` → 204. `AdminBetaService.preApprove(email, <caller>)`.

- [ ] **Step 1: 실패 테스트 작성** — `AdminUserControllerTest.java`

```java
// ADMIN JWT로:
//  GET /admin/users?status=BETA_PENDING → 200, body.data[*].status == "BETA_PENDING", body.limit 존재
//  POST /admin/users/1/approve → 204, adminBetaService.approveUser(1) 호출
//  POST /admin/allowlist {"email":"x@y.com"} → 204, preApprove("x@y.com", ...) 호출
// 비-ADMIN JWT → 위 전부 403 (B6 authz)
```
> MockMvc + `jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))`(spring-security-test) 또는 `@WithMockUser(roles="ADMIN")`. 기존 컨트롤러 테스트의 인증 셋업 방식을 실측해 일치시킨다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-platform-svc test --tests "ai.devpath.platform.beta.AdminUserControllerTest"`
Expected: FAIL — 컨트롤러 없음(404).

- [ ] **Step 3: 구현** — 커서 페이지네이션은 `id` 기반 keyset(`id > cursor` 정렬 `id ASC`, `limit` 기본 20/최대 100, `nextCursor`=마지막 id 문자열).

`AdminUserRow.java`:
```java
package ai.devpath.platform.beta.dto;

import ai.devpath.platform.user.User;

public record AdminUserRow(String id, String nickname, String email, String role, String status) {
    public static AdminUserRow of(User u) {
        return new AdminUserRow(String.valueOf(u.getId()), u.getNickname(), u.getEmail(), u.getRole(), u.getStatus());
    }
}
```

`UserRepository`에 추가:
```java
java.util.List<User> findByStatusAndIdGreaterThanOrderByIdAsc(String status, Long id, org.springframework.data.domain.Pageable pageable);
java.util.List<User> findByIdGreaterThanOrderByIdAsc(Long id, org.springframework.data.domain.Pageable pageable);
```

`AdminUserController.java`:
```java
package ai.devpath.platform.beta;

import ai.devpath.platform.beta.dto.AdminUserRow;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
public class AdminUserController {

    private final UserRepository users;
    private final AdminBetaService betaService;

    public AdminUserController(UserRepository users, AdminBetaService betaService) {
        this.users = users;
        this.betaService = betaService;
    }

    @GetMapping("/users")
    public Map<String, Object> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        int size = Math.min(Math.max(limit, 1), 100);
        long after = cursor == null || cursor.isBlank() ? 0L : Long.parseLong(cursor);
        var page = PageRequest.of(0, size);
        List<User> rows = (status == null || status.isBlank())
                ? users.findByIdGreaterThanOrderByIdAsc(after, page)
                : users.findByStatusAndIdGreaterThanOrderByIdAsc(status, after, page);
        String next = rows.size() == size ? String.valueOf(rows.get(rows.size() - 1).getId()) : null;
        return Map.of(
                "data", rows.stream().map(AdminUserRow::of).toList(),
                "nextCursor", next,   // null 허용: Map.of는 null 불가 → 아래 주석 참조
                "limit", size);
    }

    @PostMapping("/users/{id}/approve")
    public ResponseEntity<Void> approve(@PathVariable long id) {
        betaService.approveUser(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/allowlist")
    public ResponseEntity<Void> allowlist(@RequestBody Map<String, String> body) {
        betaService.preApprove(body.get("email"), "admin-api");
        return ResponseEntity.noContent().build();
    }
}
```
> **주의**: `Map.of`는 null 값 금지. `nextCursor`가 null일 수 있으므로 `list`는 `HashMap` 또는 전용 응답 record(`AdminUsersPage(List<AdminUserRow> data, String nextCursor, int limit)`)로 반환한다. 전용 record 사용을 권장(직렬화 시 `nextCursor: null` 정상 출력).

- [ ] **Step 4: 통과 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-platform-svc test --tests "ai.devpath.platform.beta.AdminUserControllerTest"`
Expected: PASS. (B6 `AdminAuthzTest`의 200 케이스가 미뤄졌다면 지금 함께 통과 확인.)

- [ ] **Step 5: 전체 회귀 + 커밋**

Run: `./gradlew -p D:/workspace/dpa/devpath-platform-svc test`
Expected: 전체 PASS.
```bash
git -C D:/workspace/dpa/devpath-platform-svc add -A && git -C D:/workspace/dpa/devpath-platform-svc commit -m "feat: /admin/users(목록·승인·사전승인) 컨트롤러"
```

### Task B9: platform PR

- [ ] `feat/beta-gating-backend` → `develop` PR. CI 녹색 확인 후 머지(merge commit). spec/plan 문서 포함.

---

## Phase C — devpath-notification-svc

브랜치: `git -C D:/workspace/dpa/devpath-notification-svc checkout develop && git -C D:/workspace/dpa/devpath-notification-svc pull && git -C D:/workspace/dpa/devpath-notification-svc checkout -b feat/beta-gating-emails`. (Phase A 발행 후 착수)

### Task C1: `EmailSender`에 주소 기반 발송 추가

**Files:**
- Modify: `.../notification/report/EmailSender.java`
- Modify: `.../notification/report/MockEmailSender.java`
- Modify: `.../notification/report/SmtpEmailSender.java`
- Test: `.../notification/report/MockEmailSenderTest.java`

**Interfaces:**
- Produces: `EmailSender.send(String toEmail, String subject, String body)` (기존 `send(long,…)` 유지 — WeeklyReportConsumer가 사용).

- [ ] **Step 1: 실패 테스트 작성** — `MockEmailSenderTest.java`

```java
package ai.devpath.notification.report;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class MockEmailSenderTest {
    @Test
    void addressBasedSend_recordsRecipient() {
        RecordingMockEmailSender s = new RecordingMockEmailSender();
        s.send("to@x.com", "subj", "body");
        assertThat(s.last().to()).isEqualTo("to@x.com");
        assertThat(s.last().subject()).isEqualTo("subj");
    }
}
```
> 테스트 검증을 위해 `MockEmailSender`에 최근 발송 기록(`record Sent(String to, String subject, String body)` + `Sent last()`)을 추가하거나, Mockito `mock(EmailSender.class)` + `verify(sender).send("to@x.com","subj","body")`로 대체 가능. 후자를 쓰면 별도 Recording 클래스 불필요 — 그 경우 이 테스트는 C2 컨슈머 테스트에 흡수.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-notification-svc test --tests "ai.devpath.notification.report.MockEmailSenderTest"`
Expected: FAIL — `send(String,String,String)` 없음(컴파일 실패).

- [ ] **Step 3: 구현**

`EmailSender.java`:
```java
public interface EmailSender {
    void send(long userId, String subject, String body);
    void send(String toEmail, String subject, String body);
}
```

`MockEmailSender.java`: 메서드 추가
```java
@Override
public void send(String toEmail, String subject, String body) {
    log.info("[MockEmailSender] to={} subject={} (실제 발송 안 함)", toEmail, subject);
}
```

`SmtpEmailSender.java`: 메서드 추가(수신자 실주소 세팅)
```java
@Override
public void send(String toEmail, String subject, String body) {
    SimpleMailMessage msg = new SimpleMailMessage();
    msg.setFrom(from);
    msg.setTo(toEmail);
    msg.setSubject(subject);
    msg.setText(body);
    mailSender.send(msg);
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-notification-svc test --tests "ai.devpath.notification.report.MockEmailSender*"`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git -C D:/workspace/dpa/devpath-notification-svc add -A && git -C D:/workspace/dpa/devpath-notification-svc commit -m "feat: EmailSender 주소 기반 발송(send(String,...))"
```

### Task C2: `BetaNotificationConsumer` (3종 매핑)

**Files:**
- Create: `.../notification/beta/BetaNotificationConsumer.java`
- Modify: `.../src/main/resources/application.yml` (`devpath.beta.notify-email`)
- Test: `.../notification/beta/BetaNotificationConsumerTest.java`

**Interfaces:**
- Consumes: `EmailSender.send(String,…)`(C1), `BetaWaitlistRegisteredEvent`/`BetaAccessApprovedEvent`(A1), `JsonMapper`(Jackson3).
- 매핑:
  - `@KafkaListener(topics="user.beta.waitlisted")` → ① 신청자(event.email) "대기명단 등록" + ② 관리자(`notifyEmail`) "신규 대기자".
  - `@KafkaListener(topics="user.beta.approved")` → 신청자(event.email) "입장 가능".
- 역직렬화 실패 시 skip(기존 poison 전략).

- [ ] **Step 1: 실패 테스트 작성**

```java
package ai.devpath.notification.beta;

import static org.mockito.Mockito.*;

import ai.devpath.notification.report.EmailSender;
import ai.devpath.shared.event.BetaAccessApprovedEvent;
import ai.devpath.shared.event.BetaWaitlistRegisteredEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BetaNotificationConsumerTest {

    private final EmailSender email = mock(EmailSender.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final BetaNotificationConsumer consumer =
            new BetaNotificationConsumer(email, mapper, "admin@devpath.ai");

    @Test
    void waitlisted_emailsApplicantAndAdmin() {
        var e = new BetaWaitlistRegisteredEvent(UUID.randomUUID(), Instant.now(), 1L, "u@x.com");
        consumer.onWaitlisted(mapper.writeValueAsString(e));
        verify(email).send(eq("u@x.com"), contains("대기"), anyString());
        verify(email).send(eq("admin@devpath.ai"), contains("신규"), anyString());
    }

    @Test
    void approved_emailsApplicant() {
        var e = new BetaAccessApprovedEvent(UUID.randomUUID(), Instant.now(), 1L, "u@x.com");
        consumer.onApproved(mapper.writeValueAsString(e));
        verify(email).send(eq("u@x.com"), contains("입장"), anyString());
    }

    @Test
    void malformed_isSkipped() {
        consumer.onWaitlisted("{ not json");
        verifyNoInteractions(email);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-notification-svc test --tests "ai.devpath.notification.beta.BetaNotificationConsumerTest"`
Expected: FAIL — `BetaNotificationConsumer` 심볼 없음.

- [ ] **Step 3: 구현**

```java
package ai.devpath.notification.beta;

import ai.devpath.notification.report.EmailSender;
import ai.devpath.shared.event.BetaAccessApprovedEvent;
import ai.devpath.shared.event.BetaWaitlistRegisteredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BetaNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(BetaNotificationConsumer.class);
    private final EmailSender email;
    private final JsonMapper jsonMapper;
    private final String notifyEmail;

    public BetaNotificationConsumer(EmailSender email, JsonMapper jsonMapper,
            @Value("${devpath.beta.notify-email:}") String notifyEmail) {
        this.email = email;
        this.jsonMapper = jsonMapper;
        this.notifyEmail = notifyEmail;
    }

    @KafkaListener(topics = "user.beta.waitlisted", groupId = "devpath-notification")
    public void onWaitlisted(String payload) {
        BetaWaitlistRegisteredEvent e;
        try { e = jsonMapper.readValue(payload, BetaWaitlistRegisteredEvent.class); }
        catch (Exception ex) { log.warn("waitlisted 역직렬화 실패 skip: {}", payload, ex); return; }
        email.send(e.email(), "DevPath 베타 대기명단에 등록되었습니다",
                "베타 대기명단에 등록되었습니다. 승인되면 이메일로 알려드립니다.");
        if (notifyEmail != null && !notifyEmail.isBlank()) {
            email.send(notifyEmail, "[DevPath] 신규 베타 대기자",
                    "신규 베타 대기자: " + e.email() + " (userId=" + e.userId() + ")");
        }
    }

    @KafkaListener(topics = "user.beta.approved", groupId = "devpath-notification")
    public void onApproved(String payload) {
        BetaAccessApprovedEvent e;
        try { e = jsonMapper.readValue(payload, BetaAccessApprovedEvent.class); }
        catch (Exception ex) { log.warn("approved 역직렬화 실패 skip: {}", payload, ex); return; }
        email.send(e.email(), "DevPath 베타 입장이 승인되었습니다",
                "축하합니다! 이제 DevPath 데모에 로그인하실 수 있습니다.");
    }
}
```

- [ ] **Step 4: application.yml에 notify-email 추가** (기존 `devpath.mail` 블록 옆)

```yaml
devpath:
  beta:
    notify-email: ${BETA_NOTIFY_EMAIL:}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew -p D:/workspace/dpa/devpath-notification-svc test --tests "ai.devpath.notification.beta.BetaNotificationConsumerTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: 전체 회귀 + 커밋**

Run: `./gradlew -p D:/workspace/dpa/devpath-notification-svc test`
Expected: 전체 PASS.
```bash
git -C D:/workspace/dpa/devpath-notification-svc add -A && git -C D:/workspace/dpa/devpath-notification-svc commit -m "feat: 베타 대기/승인 이메일 컨슈머 3종"
```

### Task C3: notification PR

- [ ] `feat/beta-gating-emails` → `develop` PR. CI 녹색 후 머지.

---

## 통합 스모크 (선택, 배포 전)

- [ ] platform bootRun + notification bootRun + 로컬 Kafka(`devpath-shared/docker compose up -d`)로:
  1. 미승인 이메일로 OAuth 로그인 → `/login?beta=pending` 리다이렉트 + (mock 로그) 대기/관리자 이메일 로깅 확인.
  2. ADMIN JWT로 `POST /admin/users/{id}/approve` → 204 + (mock 로그) 승인 이메일 로깅.
  3. 승인된 사용자 재로그인 → `/auth/callback` + 토큰 발급.

---

## Self-Review (플랜↔스펙)

- **스펙 §2 데이터 모델**: A2(마이그레이션)·B1(엔티티)·B3/B4(BETA_PENDING 전이) 커버. ✅
- **스펙 §3 이벤트·알림**: A1(이벤트)·B3/B7(발행)·C1/C2(수신·발송) 커버. 3종 알림 = C2 onWaitlisted(①②)+onApproved(③). ✅
- **스펙 §4 ADMIN role+API**: B5(시딩)·B6(authz)·B7/B8(API) 커버. ✅
- **스펙 §5 프론트**: C2 비범위(pending 리다이렉트만 B4에서 처리). ✅ (C2 워크스트림)
- **스펙 §6 테스트**: 각 Task가 실패테스트 선행. ✅
- **타입 일관성**: `BetaGate.admit(User):boolean`, `normalize(String):String`, `AdminBetaService.approveUser(long)/preApprove(String,String)`, `EmailSender.send(String,String,String)`, 이벤트 `email()`/`userId()`/`EVENT_TYPE` — 전 태스크 참조 일치. ✅
- **오픈 이슈**: (a) `Map.of` null 금지 → B8에서 전용 응답 record 사용(명시). (b) 커서=id keyset(B8 명시). (c) `@Transactional` 자가호출은 단일 트랜잭션으로 수용(B7 명시).
