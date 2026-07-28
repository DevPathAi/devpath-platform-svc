# Refresh 토큰 재사용 감지 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 회전된 refresh 토큰이 유예창(grace) 밖에서 재제시되면 탈취로 간주해 해당 사용자의 전 세션을 폐기한다.

**Architecture:** `RefreshTokenStore.rotate()` 안에서 완결한다. 회전 시 `refresh:<hash>` 값에 회전 시각(`grace:<userId>:<rotatedAtMillis>`)을 부가하고 TTL을 `refreshTtl`로 늘려 **묘비(tombstone)**로 남긴다. 재제시 시 `now − rotatedAt`이 grace 이내면 정상 동시-refresh, 초과면 재사용 신호로 보고 `revokeAll(userId)`를 호출한다. 킬스위치(`refresh-reuse-detection`)로 즉시 끌 수 있다.

**Tech Stack:** Spring Boot 4.0.7 · Java 21 · Gradle(Kotlin DSL) · Redis(StringRedisTemplate) · JUnit 5.

**설계 SSoT:** `docs/superpowers/specs/2026-07-28-refresh-reuse-detection-design.md`

## Global Constraints

- 패키지 `ai.devpath.platform`, 메인 `PlatformApplication`. 들여쓰기는 **탭**(기존 파일 관례 — `RefreshTokenStore.java`·`AuthProperties.java` 모두 탭).
- **TDD 강제**: 모든 변경은 실패하는 테스트를 먼저 작성하고, 통과를 눈으로 확인한다. 테스트 없는 구현 금지.
- 테스트 실행: `./gradlew test` (JUnit 5). `RefreshTokenStoreTest`는 `@SpringBootTest` + `@ActiveProfiles("test")`로 **실 Redis** 사용. 기존 테스트 클래스에 메서드만 추가한다(신규 `@SpringBootTest` 클래스 추가 금지 — 다중 컨텍스트×Hikari 풀로 "too many clients" flake 유발).
- `AuthController` 시그니처·로직 **무변경**. `rotate()`는 재사용 시에도 기존과 동일하게 `Optional.empty()` 반환(→ 컨트롤러가 401). 폐기는 스토어 내부에서 수행.
- 킬스위치 기본값 **`true`**(enforce). 보안 기본값이므로 절대 `false`로 커밋 금지.
- 토큰 원문·해시는 **로깅 금지**(userId만).
- 브랜치 `feat/refresh-reuse-detection`(이미 origin/develop 기반 생성됨). `develop`·`main` 직접 커밋 금지. Conventional Commits.
- 커밋 메시지 끝에 트레일러 추가: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- `.gitignore`·`.omc/`는 무관한 미커밋 상태 — **절대 스테이징하지 말 것**. 각 태스크는 지정한 파일만 `git add`.

---

## File Structure

- Modify `src/main/java/ai/devpath/platform/config/AuthProperties.java` — 킬스위치 플래그 필드 + 접근자.
- Modify `src/main/java/ai/devpath/platform/auth/refresh/RefreshTokenStore.java` — 묘비 포맷·TTL, `parseUserId` 보강, `parseRotatedAt` 추가, `rotate()` 재사용 분기, 로거.
- Modify `src/test/java/ai/devpath/platform/auth/refresh/RefreshTokenStoreTest.java` — 기본값 가드 테스트 + 재사용 3종 테스트, 2-인자 `store()` 헬퍼.

---

## Task 1: 킬스위치 설정 플래그

킬스위치 `devpath.auth.refresh-reuse-detection`를 `AuthProperties`에 추가한다. 보안 기본값(enforce)을 가드 테스트로 못박는다. 이 태스크만으로는 동작 변화가 없다(플래그는 Task 2에서 소비).

**Files:**
- Modify: `src/main/java/ai/devpath/platform/config/AuthProperties.java`
- Test: `src/test/java/ai/devpath/platform/auth/refresh/RefreshTokenStoreTest.java`

**Interfaces:**
- Produces: `AuthProperties.isRefreshReuseDetection() : boolean` (기본 `true`), `AuthProperties.setRefreshReuseDetection(boolean)`.

- [ ] **Step 1: 실패 테스트 작성** — `RefreshTokenStoreTest`에 아래 메서드 추가.

```java
	@Test
	void reuseDetectionEnabledByDefault() {
		assertTrue(new AuthProperties().isRefreshReuseDetection(),
				"보안 기본값: 재사용 감지는 기본 활성(enforce)");
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "ai.devpath.platform.auth.refresh.RefreshTokenStoreTest.reuseDetectionEnabledByDefault"`
Expected: **컴파일 실패** — `isRefreshReuseDetection()` 심볼 없음.

- [ ] **Step 3: 플래그 추가** — `AuthProperties.java`에서 `refreshRotateGrace` 필드 바로 아래(현 12행)에 필드를, 접근자 묶음(현 28~29행 `refreshRotateGrace` 접근자) 아래에 getter/setter를 추가.

필드:
```java
	private boolean refreshReuseDetection = true;
```

접근자:
```java
	public boolean isRefreshReuseDetection() { return refreshReuseDetection; }
	public void setRefreshReuseDetection(boolean v) { this.refreshReuseDetection = v; }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "ai.devpath.platform.auth.refresh.RefreshTokenStoreTest.reuseDetectionEnabledByDefault"`
Expected: **PASS**

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/ai/devpath/platform/config/AuthProperties.java \
        src/test/java/ai/devpath/platform/auth/refresh/RefreshTokenStoreTest.java
git commit -m "$(cat <<'MSG'
feat: refresh-reuse-detection 킬스위치 플래그 추가(기본 on)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
MSG
)"
```

---

## Task 2: 타임스탬프 묘비 + 재사용 감지

`rotate()`가 회전 시각을 묘비에 기록하고, 유예창 밖 재제시를 탐지해 `revokeAll`로 전 세션을 폐기한다.

**Files:**
- Modify: `src/main/java/ai/devpath/platform/auth/refresh/RefreshTokenStore.java`
- Test: `src/test/java/ai/devpath/platform/auth/refresh/RefreshTokenStoreTest.java`

**Interfaces:**
- Consumes: `AuthProperties.isRefreshReuseDetection()`(Task 1), `AuthProperties.getRefreshRotateGrace()`, `getRefreshTtl()`, 기존 `issue()`·`revokeAll()`·`hash()`.
- Produces: 동작 계약만 변경. 공개 시그니처(`rotate`, `validate`, `revoke`, `revokeAll`, `Rotated`) **불변**.

- [ ] **Step 1: 테스트 헬퍼 오버로드 추가** — 기존 1-인자 `store(Duration)`를 2-인자 위임으로 리팩터(DRY). `RefreshTokenStoreTest`의 기존 헬퍼(현 22~27행)를 아래로 교체.

```java
	private RefreshTokenStore store(Duration grace) {
		return store(grace, true);
	}

	private RefreshTokenStore store(Duration grace, boolean reuseDetection) {
		AuthProperties props = new AuthProperties();
		props.setRefreshTtl(Duration.ofDays(14));
		props.setRefreshRotateGrace(grace);
		props.setRefreshReuseDetection(reuseDetection);
		return new RefreshTokenStore(redis, props);
	}
```

- [ ] **Step 2: 재사용 3종 실패 테스트 작성** — `RefreshTokenStoreTest`에 추가.

```java
	@Test
	void reuseOutsideGraceRevokesAllSessions() throws InterruptedException {
		RefreshTokenStore s = store(Duration.ofMillis(80), true);
		String t1 = s.issue(100L);
		String t2 = s.issue(100L);              // 다른 기기(2세션)
		var n1 = s.rotate(t1).orElseThrow();    // t1 → 묘비, 신규 n1 발급
		Thread.sleep(300);                      // 유예창(80ms) 밖으로
		assertFalse(s.rotate(t1).isPresent(), "유예창 밖 재사용 → empty(401)");
		// 탈취 감지 → 전 세션 폐기
		assertFalse(s.validate(t2).isPresent(), "다른 세션 토큰도 폐기");
		assertFalse(s.validate(n1.newToken()).isPresent(), "회전으로 받은 신규 토큰도 폐기");
	}

	@Test
	void reuseWithinGraceDoesNotRevoke() {
		RefreshTokenStore s = store(Duration.ofSeconds(30), true);
		String t1 = s.issue(101L);
		String t2 = s.issue(101L);
		s.rotate(t1).orElseThrow();             // 묘비 생성
		assertTrue(s.rotate(t1).isPresent(), "유예창 안 재사용은 정상(새 토큰)");
		assertTrue(s.validate(t2).isPresent(), "다른 세션은 폐기되지 않음");
	}

	@Test
	void reuseOutsideGraceWithDetectionOffDoesNotRevoke() throws InterruptedException {
		RefreshTokenStore s = store(Duration.ofMillis(80), false);   // 킬스위치 off
		String t1 = s.issue(102L);
		String t2 = s.issue(102L);
		s.rotate(t1).orElseThrow();
		Thread.sleep(300);
		assertFalse(s.rotate(t1).isPresent(), "detection off라도 스테일 토큰은 여전히 거부(401)");
		assertTrue(s.validate(t2).isPresent(), "단, off면 다른 세션은 유지(revokeAll 미실행)");
	}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --tests "ai.devpath.platform.auth.refresh.RefreshTokenStoreTest"`
Expected: 신규 3종 **FAIL** — 현 `rotate()`는 유예창 밖 재제시를 `null`(만료)로만 처리해 revokeAll을 호출하지 않음. `reuseOutsideGraceRevokesAllSessions`에서 `validate(t2)`가 여전히 present.

- [ ] **Step 4: `RefreshTokenStore` 구현** — 아래 4개 지점을 수정.

(a) import 추가(파일 상단 import 블록):
```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

(b) 로거 필드 — `RANDOM` 상수(현 22행) 아래에 추가:
```java
	private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);
```

(c) `rotate()` 전체(현 56~71행)를 아래로 교체:
```java
	public Optional<Rotated> rotate(String oldToken) {
		if (oldToken == null || oldToken.isBlank()) return Optional.empty();
		String key = PREFIX + hash(oldToken);
		String value = redis.opsForValue().get(key);
		if (value == null) return Optional.empty();
		long userId = parseUserId(value);
		Duration grace = props.getRefreshRotateGrace();
		boolean graceEnabled = grace != null && !grace.isZero() && !grace.isNegative();

		if (!value.startsWith(GRACE_PREFIX)) {
			// 현행 토큰 회전
			if (!graceEnabled) {
				redis.delete(key); // 유예 비활성: 엄격 단일-사용
			} else {
				// 묘비: 회전 시각을 부가하고 TTL을 refreshTtl로 늘려, 유예창 밖 재사용을 감지 가능하게 남긴다.
				redis.opsForValue().set(key, GRACE_PREFIX + userId + ":" + System.currentTimeMillis(),
						props.getRefreshTtl());
			}
			return Optional.of(new Rotated(userId, issue(userId)));
		}

		// 이미 회전된(묘비) 토큰 재제시
		Long rotatedAt = parseRotatedAt(value); // 구(舊) 2-파트 마커면 null
		boolean withinGrace = rotatedAt == null
				|| (graceEnabled && (System.currentTimeMillis() - rotatedAt) <= grace.toMillis());
		if (withinGrace) {
			// 정상 동시-refresh: 마커 불변(연장 금지), 새 토큰만 발급
			return Optional.of(new Rotated(userId, issue(userId)));
		}

		// 유예창 밖 재사용 = 탈취 신호
		if (props.isRefreshReuseDetection()) {
			log.warn("refresh token reuse detected for user {} — revoking all sessions", userId);
			revokeAll(userId);
		}
		return Optional.empty();
	}
```

(d) `parseUserId`(현 73~76행)를 3-파트 내성 버전으로 교체하고, `parseRotatedAt`를 바로 아래에 신설:
```java
	private static long parseUserId(String value) {
		String s = value.startsWith(GRACE_PREFIX) ? value.substring(GRACE_PREFIX.length()) : value;
		int colon = s.indexOf(':');
		if (colon >= 0) s = s.substring(0, colon);
		return Long.parseLong(s);
	}

	private static Long parseRotatedAt(String value) {
		// "grace:<userId>:<millis>" 에서 millis 추출. 구 포맷 "grace:<userId>"면 null.
		int firstColon = value.indexOf(':');
		int secondColon = value.indexOf(':', firstColon + 1);
		if (secondColon < 0) return null;
		return Long.parseLong(value.substring(secondColon + 1));
	}
```

- [ ] **Step 5: 신규 테스트 통과 확인**

Run: `./gradlew test --tests "ai.devpath.platform.auth.refresh.RefreshTokenStoreTest"`
Expected: 신규 3종 + 기존 6종 모두 **PASS**. (기존 `graceExpires`·`graceReuseDoesNotExtendGraceTtl`는 반환값 단언이 그대로 유지되고, `graceTokenStillValidates`는 이제 3-파트 묘비에서 userId를 파싱하는 경로를 자연히 커버.)

- [ ] **Step 6: 전체 회귀 확인**

Run: `./gradlew test`
Expected: **BUILD SUCCESSFUL**, 전체 그린.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/ai/devpath/platform/auth/refresh/RefreshTokenStore.java \
        src/test/java/ai/devpath/platform/auth/refresh/RefreshTokenStoreTest.java
git commit -m "$(cat <<'MSG'
feat: refresh 토큰 재사용 감지 — 유예창 밖 재사용 시 전 세션 폐기

회전 시각을 묘비에 기록(grace:<userId>:<millis>, TTL=refreshTtl)하고,
now-rotatedAt > grace면 탈취로 간주해 revokeAll. AuthController 무변경.
킬스위치 refresh-reuse-detection로 즉시 비활성 가능.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
MSG
)"
```

---

## 배포·검증 (구현 후 — 코드 태스크 아님)

스펙 §6 참조. 요약: `feat/refresh-reuse-detection` → develop PR(CI 그린) → develop→main PR(릴리스) → ArgoCD 반영 → 운영 재검증(EC2에서 테스트 토큰 삽입: 유예창 안=200·세션 유지 / 유예창 밖=401·`refresh:byUser:<id>` 전멸, 종료 시 잔여물 삭제) → gitops 런북에 기록.

---

## Self-Review

**1. Spec coverage** (스펙 §별 → 태스크 매핑):
- §3.1 저장 포맷(`grace:<id>:<millis>`, TTL=refreshTtl) → Task 2 Step 4(c).
- §3.2 rotate 규칙(null / 현행 / 묘비 within·outside) → Task 2 Step 4(c).
- §3.3 두 스위치(grace, reuse-detection) → grace는 기존, 플래그는 Task 1 + Task 2 소비.
- §3.4 parseUserId 보강, validate/revoke/revokeAll 불변 → Task 2 Step 4(d); validate 무변경(3-파트 파싱은 parseUserId 경유).
- §3.5 WARN 로그(userId만), Micrometer는 후속 → Task 2 Step 4(b)(c).
- §5 테스트: 기존 6 유지 + 신규(reuse outside/within, detection off) → Task 2 Step 2·5·6. 신규 #4(타임스탬프 파싱)는 기존 `graceTokenStillValidates`가 Task 2 이후 3-파트 경로를 커버하므로 별도 태스크 불요. 컨트롤러 통합은 `AuthController` 무변경 + 기존 401 테스트로 커버(신규 불요).
- 갭 없음.

**2. Placeholder scan:** "TBD/추후/적절히" 등 없음. 모든 코드 단계에 실제 코드 포함.

**3. Type consistency:** `isRefreshReuseDetection()`(Task 1 생성 ↔ Task 2 소비) 일치. `parseUserId`/`parseRotatedAt` 시그니처와 호출 일치. `Rotated(long, String)`·`issue(long)`·`revokeAll(long)` 기존 시그니처 사용. `GRACE_PREFIX`="grace:" 재사용.
