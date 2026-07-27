# Refresh 회전 유예창(Rotation Grace Window) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 동시 `/auth/refresh` 호출이 401로 세션을 파괴하지 않도록 `RefreshTokenStore`에 회전 유예창(기본 30초)을 도입하고, 릴리스 후 운영에서 실검증한다.

**Architecture:** `refresh:<hash>` 값에 `grace:<userId>` 마커 형태를 추가한다. `rotate()`는 현행 토큰을 DEL 대신 유예 마커(짧은 TTL)로 교체하고, 유예 토큰으로 온 재회전도 새 토큰을 발급한다. 컨트롤러·클라이언트 변경 없음. 스펙: `docs/superpowers/specs/2026-07-27-refresh-rotate-grace-design.md`.

**Tech Stack:** Spring Boot 4.0.7 · Java 21 · Gradle(Kotlin DSL) · Redis(StringRedisTemplate) · JUnit 5 · `@SpringBootTest`(test 프로필, 실 Redis/Postgres)

## Global Constraints

- 레포: `D:\workspace\dpa\devpath-platform-svc`, 작업 브랜치 `fix/refresh-rotate-grace`(← develop, 이미 생성·스펙 커밋 491195d 존재). develop·main 직접 푸시 금지, PR 경유(머지 전 CI 녹색 필수, 기본 merge commit).
- CLAUDE.md 절대 조건: 추측 금지·Test-First·자화자찬 금지(모든 완료 주장은 실행 결과로 증명).
- Conventional Commits + 커밋 말미 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- 테스트 전제: localhost Postgres(devpath/localdev, DB devpath, pgvector pg17)와 Redis(6379) — CI(ci.yml services)와 동일 값.
- Windows 주의: EC2 ssh는 PowerShell(Windows OpenSSH)로만(`ssh -i $env:USERPROFILE\.ssh\devpath-k3s-key.pem ubuntu@13.124.153.105`) — Git Bash ssh는 키 로드 실패(libcrypto). 원격 명령은 PowerShell 작은따옴표 문자열로 감싼다(`$` 이스케이프 문제 회피).
- gradle 네트워크 이슈 시 `--offline` 폴백(과거 로컬 QA에서 사용).

---

### Task 1: 회전 유예창 TDD (store + controller)

**Files:**
- Modify: `src/main/java/ai/devpath/platform/config/AuthProperties.java`
- Modify: `src/main/java/ai/devpath/platform/auth/refresh/RefreshTokenStore.java`
- Test: `src/test/java/ai/devpath/platform/auth/refresh/RefreshTokenStoreTest.java`
- Test: `src/test/java/ai/devpath/platform/auth/AuthControllerTest.java:26-45` (기존 I-1 단언 뒤집기)

**Interfaces:**
- Consumes: 기존 `RefreshTokenStore.issue/validate/rotate/revoke/revokeAll` 시그니처 (변경 없음).
- Produces: `AuthProperties.getRefreshRotateGrace(): Duration` / `setRefreshRotateGrace(Duration)` (기본 30초, 프로퍼티 `devpath.auth.refresh-rotate-grace`). `rotate()` 의미 변경: 유예창 내 직전 토큰 재사용 → `Optional<Rotated>` 성공.

- [ ] **Step 1: 테스트 인프라 프리플라이트**

Run(PowerShell): `docker ps --format "{{.Names}} {{.Ports}}"`
Expected: 5432(postgres)·6379(redis) 컨테이너 확인. 없으면 시작:

```powershell
docker run -d --name devpath-pg -e POSTGRES_USER=devpath -e POSTGRES_PASSWORD=localdev -e POSTGRES_DB=devpath -p 5432:5432 pgvector/pgvector:pg17
docker run -d --name devpath-redis -p 6379:6379 redis:7-alpine
```

(기존 중지 컨테이너가 있으면 `docker start <name>` 우선.)

- [ ] **Step 2: RefreshTokenStoreTest에 실패 테스트 작성**

`src/test/java/ai/devpath/platform/auth/refresh/RefreshTokenStoreTest.java` 전체를 아래로 교체:

```java
package ai.devpath.platform.auth.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.devpath.platform.config.AuthProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RefreshTokenStoreTest {

	@Autowired StringRedisTemplate redis;

	private RefreshTokenStore store(Duration grace) {
		AuthProperties props = new AuthProperties();
		props.setRefreshTtl(Duration.ofDays(14));
		props.setRefreshRotateGrace(grace);
		return new RefreshTokenStore(redis, props);
	}

	@Test
	void issueValidateRotateRevoke_singleUseWhenGraceDisabled() {
		RefreshTokenStore s = store(Duration.ZERO);
		String t = s.issue(42L);
		assertEquals(42L, s.validate(t).orElseThrow());

		var rotated = s.rotate(t).orElseThrow();
		assertEquals(42L, rotated.userId());
		assertFalse(s.validate(t).isPresent(), "grace=0이면 회전 후 이전 토큰 즉시 무효");
		assertFalse(s.rotate(t).isPresent(), "grace=0이면 이전 토큰 재회전 불가");
		assertEquals(42L, s.validate(rotated.newToken()).orElseThrow());

		s.revoke(rotated.newToken());
		assertFalse(s.validate(rotated.newToken()).isPresent(), "폐기 후 무효");
	}

	@Test
	void rotateWithinGraceAllowsConcurrentReuse() {
		RefreshTokenStore s = store(Duration.ofSeconds(30));
		String t = s.issue(7L);

		var first = s.rotate(t).orElseThrow();
		// 유예창 내 동일(직전) 토큰 재회전 — 동시 refresh/멀티탭 시나리오.
		var second = s.rotate(t).orElseThrow();

		assertEquals(7L, first.userId());
		assertEquals(7L, second.userId());
		assertNotEquals(first.newToken(), second.newToken(), "재사용마다 새 토큰 발급");
		assertNotEquals(t, first.newToken());
		assertEquals(7L, s.validate(first.newToken()).orElseThrow());
		assertEquals(7L, s.validate(second.newToken()).orElseThrow());
	}

	@Test
	void graceTokenStillValidates() {
		RefreshTokenStore s = store(Duration.ofSeconds(30));
		String t = s.issue(8L);
		s.rotate(t).orElseThrow();
		assertEquals(8L, s.validate(t).orElseThrow(), "유예 토큰은 validate에서 유효");
	}

	@Test
	void revokeKillsGraceToken() {
		RefreshTokenStore s = store(Duration.ofSeconds(30));
		String t = s.issue(9L);
		s.rotate(t).orElseThrow();
		s.revoke(t);
		assertFalse(s.rotate(t).isPresent(), "revoke된 유예 토큰은 재회전 불가");
		assertFalse(s.validate(t).isPresent());
	}

	@Test
	void graceExpires() throws InterruptedException {
		RefreshTokenStore s = store(Duration.ofMillis(80));
		String t = s.issue(10L);
		s.rotate(t).orElseThrow();
		Thread.sleep(300);
		assertFalse(s.rotate(t).isPresent(), "유예창 만료 후 이전 토큰 무효");
		assertFalse(s.validate(t).isPresent());
	}

	@Test
	void graceReuseDoesNotExtendGraceTtl() throws InterruptedException {
		// grace=400ms. t=300ms 재사용(유효) 후 t=550ms 재확인:
		// 연장 없으면 400ms에 만료(→empty 기대), 연장 버그면 300+400=700ms까지 살아있어 실패.
		RefreshTokenStore s = store(Duration.ofMillis(400));
		String t = s.issue(11L);
		s.rotate(t).orElseThrow();
		Thread.sleep(300);
		assertTrue(s.rotate(t).isPresent(), "만료 전 재사용 가능");
		Thread.sleep(250);
		assertFalse(s.rotate(t).isPresent(), "유예 재사용이 TTL을 연장하면 안 됨");
	}
}
```

- [ ] **Step 3: AuthControllerTest I-1 단언 뒤집기 (401 → 200)**

`src/test/java/ai/devpath/platform/auth/AuthControllerTest.java`의 `refreshWithValidCookieReturnsAccessTokenAndRotates` 마지막 블록(42-44행)을 교체:

기존:

```java
		// I-1: 첫 호출 성공(200) 후 — 동일 이전 토큰 재사용은 회전으로 무효 → 401
		mvc.perform(post("/auth/refresh").cookie(new Cookie("refresh_token", refresh)))
				.andExpect(status().isUnauthorized());
```

신규:

```java
		// 회전 유예창: 직전 토큰 재사용(동시 refresh·멀티탭·콜백 이중 부트스트랩)은
		// 유예창(기본 30s) 내 200 — 각자 새 access와 새 refresh 쿠키를 받는다.
		mvc.perform(post("/auth/refresh").cookie(new Cookie("refresh_token", refresh)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.access_token").isNotEmpty())
				.andExpect(header().exists("Set-Cookie"));
```

- [ ] **Step 4: AuthProperties에 유예창 프로퍼티 추가 (테스트 컴파일 가능화)**

`src/main/java/ai/devpath/platform/config/AuthProperties.java` — `authCodeTtl` 필드 아래에 추가:

```java
	private Duration refreshRotateGrace = Duration.ofSeconds(30);
```

getter/setter 블록(`getAuthCodeTtl`/`setAuthCodeTtl` 아래)에 추가:

```java
	public Duration getRefreshRotateGrace() { return refreshRotateGrace; }
	public void setRefreshRotateGrace(Duration v) { this.refreshRotateGrace = v; }
```

- [ ] **Step 5: RED 확인 — 신규 테스트가 실패하는지 실행**

Run: `./gradlew test --tests "ai.devpath.platform.auth.refresh.RefreshTokenStoreTest" --tests "ai.devpath.platform.auth.AuthControllerTest"`
Expected: **FAIL 4건** — `rotateWithinGraceAllowsConcurrentReuse`(재회전 empty → orElseThrow 예외), `graceTokenStillValidates`(회전 후 validate empty), `graceReuseDoesNotExtendGraceTtl`(만료 전 재사용 assertTrue 실패), `refreshWithValidCookieReturnsAccessTokenAndRotates`(재사용 401 ≠ 200). 나머지(`issueValidateRotateRevoke_singleUseWhenGraceDisabled`·`revokeKillsGraceToken`·`graceExpires`)는 현행 코드에서도 단언이 성립해 통과한다 — 회귀 고정 역할.

- [ ] **Step 6: RefreshTokenStore 유예 회전 구현**

`src/main/java/ai/devpath/platform/auth/refresh/RefreshTokenStore.java`에서 `rotate`·`validate`를 교체하고 상수·헬퍼를 추가:

```java
	private static final String PREFIX = "refresh:";
	private static final String BY_USER_PREFIX = "refresh:byUser:";
	private static final String GRACE_PREFIX = "grace:";
	private static final SecureRandom RANDOM = new SecureRandom();
```

```java
	public Optional<Long> validate(String token) {
		if (token == null || token.isBlank()) return Optional.empty();
		String v = redis.opsForValue().get(PREFIX + hash(token));
		return v == null ? Optional.empty() : Optional.of(parseUserId(v));
	}

	/**
	 * 회전: 현행 토큰은 삭제 대신 짧은 유예 마커(grace:<userId>)로 교체해, 이미 전송 중인
	 * 동시 refresh(콜백 이중 부트스트랩·멀티탭)가 401로 세션을 파괴하지 않게 한다.
	 * 유예 토큰 재사용도 새 토큰을 발급하되 마커 TTL은 연장하지 않는다. grace<=0이면 기존 단일-사용.
	 */
	public Optional<Rotated> rotate(String oldToken) {
		if (oldToken == null || oldToken.isBlank()) return Optional.empty();
		String key = PREFIX + hash(oldToken);
		String value = redis.opsForValue().get(key);
		if (value == null) return Optional.empty();
		long userId = parseUserId(value);
		if (!value.startsWith(GRACE_PREFIX)) {
			Duration grace = props.getRefreshRotateGrace();
			if (grace == null || grace.isZero() || grace.isNegative()) {
				redis.delete(key);
			} else {
				redis.opsForValue().set(key, GRACE_PREFIX + userId, grace);
			}
		}
		return Optional.of(new Rotated(userId, issue(userId)));
	}

	private static long parseUserId(String value) {
		return Long.parseLong(
				value.startsWith(GRACE_PREFIX) ? value.substring(GRACE_PREFIX.length()) : value);
	}
```

(그 외 `issue`/`revoke`/`revokeAll`/`hash`는 무변경. `import java.time.Duration;` 추가 필요.)

- [ ] **Step 7: GREEN 확인**

Run: `./gradlew test --tests "ai.devpath.platform.auth.refresh.RefreshTokenStoreTest" --tests "ai.devpath.platform.auth.AuthControllerTest"`
Expected: **BUILD SUCCESSFUL**, 전 테스트 PASS.

- [ ] **Step 8: 커밋**

```powershell
git add src/main/java/ai/devpath/platform/config/AuthProperties.java src/main/java/ai/devpath/platform/auth/refresh/RefreshTokenStore.java src/test/java/ai/devpath/platform/auth/refresh/RefreshTokenStoreTest.java src/test/java/ai/devpath/platform/auth/AuthControllerTest.java
git commit -m @'
fix: refresh 회전에 유예창 도입 — 동시 refresh 401 세션 파괴 해소

비원자적 단일-사용 회전이 콜백 이중 부트스트랩·멀티탭의 동시
/auth/refresh와 충돌해 한쪽이 401을 받고 인터셉터 store.clear()로
세션이 파괴되던 문제(운영 실측 {200,401})의 근본 수정.
회전 시 DEL 대신 grace:<userId> 마커(기본 30s, devpath.auth.refresh-rotate-grace,
0=비활성)로 교체하고 유예 내 재사용은 각자 새 토큰을 발급한다(TTL 연장 없음).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 2: 전체 회귀 + develop PR

**Files:**
- 없음 (검증·PR만)

**Interfaces:**
- Consumes: Task 1 커밋 완료 상태의 `fix/refresh-rotate-grace` 브랜치.
- Produces: develop에 머지된 수정 (릴리스 입력).

- [ ] **Step 1: 전체 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (실패 시 수정 금지 — systematic-debugging으로 원인 규명 후 처리).

- [ ] **Step 2: 푸시 + PR 생성**

```powershell
git push -u origin fix/refresh-rotate-grace
gh pr create --base develop --title "fix: refresh 회전 유예창 — 동시 refresh 401 세션 파괴 해소" --body @'
## 문제
로그인 → 동의 화면에서 /auth/refresh·/dashboard/me 401 (운영 🔴 OPEN). 콜백 착지 시 웹이 /auth/refresh를 동시 2회+α 호출하는데, rotate()가 validate→DEL→issue 비원자적 단일-사용이라 뒤따른 요청이 401 → AuthInterceptor store.clear()로 세션 파괴. 운영 실측: 동시 2회 {200,401} 및 이중 발급 {200,200} 재현.

## 수정
- rotate(): 현행 토큰을 DEL 대신 grace:<userId> 마커(TTL=devpath.auth.refresh-rotate-grace, 기본 30s)로 교체. 유예 내 재사용은 각자 새 토큰 발급(마커 TTL 연장 없음). grace<=0 = 기존 단일-사용.
- validate(): 유예 마커도 userId로 유효 판정. revoke/revokeAll 무변경(DEL이 마커에도 동작, byUser 역인덱스 유지).
- 컨트롤러·클라이언트 변경 없음.

## 테스트
- RefreshTokenStoreTest: 유예 재사용/validate/revoke/만료/TTL 비연장/grace=0 단일-사용 6케이스.
- AuthControllerTest: 직전 쿠키 재사용 401→200으로 계약 갱신 (red→green 확인).

스펙: docs/superpowers/specs/2026-07-27-refresh-rotate-grace-design.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)
'@
```

- [ ] **Step 3: CI 녹색 확인 후 머지**

Run: `gh pr checks <PR번호> --watch` → 전부 pass 확인 후:

```powershell
gh pr merge <PR번호> --merge
```

Expected: develop에 merge commit. `gh pr view <PR번호> --json state,mergedAt`로 머지 사실 직접 재확인.

---

### Task 3: 릴리스 (develop → main) + 배포 반영 확인

**Files:**
- 없음 (릴리스·배포 관찰만)

**Interfaces:**
- Consumes: Task 2에서 develop에 머지된 커밋.
- Produces: 운영 클러스터에 새 platform 이미지(main 머지 SHA 태그) 배포 완료 상태.

- [ ] **Step 1: 릴리스 PR**

```powershell
gh pr create --base main --head develop --title "release: refresh 회전 유예창 (동의화면 401 수정)" --body @'
develop → main 릴리스: refresh 회전 유예창(grace window) — 운영 🔴 OPEN(로그인 후 동의화면 401) 근본 수정.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
'@
```

- [ ] **Step 2: CI 녹색 → 머지**

Run: `gh pr checks <PR번호> --watch` → pass 후 `gh pr merge <PR번호> --merge`.

- [ ] **Step 3: main CI(image+deploy) 완료 확인**

Run: `gh run list --branch main --limit 3` → 최신 CI run id로 `gh run watch <run-id>`
Expected: build·image·deploy 잡 성공 (deploy 잡이 gitops에 `deploy(platform-svc): <sha>` 커밋 푸시).

- [ ] **Step 4: ArgoCD 반영 확인 (PowerShell ssh)**

```powershell
ssh -i $env:USERPROFILE\.ssh\devpath-k3s-key.pem ubuntu@13.124.153.105 'export KUBECONFIG=/etc/rancher/k3s/k3s.yaml; kubectl -n devpath get deploy devpath-platform-svc -o jsonpath="{.spec.template.spec.containers[0].image}"; echo; kubectl -n devpath get pods -l app=devpath-platform-svc'
```

Expected: 이미지 태그 = main 머지 커밋 SHA, pod Running(신규). 반영이 늦으면 argocd application hard refresh annotate(런북 절차).
(라벨 셀렉터가 다르면 `kubectl -n devpath get pods | grep platform`으로 대체.)

---

### Task 4: 운영 재검증 + 런북 갱신

**Files:**
- Modify: `D:\workspace\dpa\devpath-gitops\docs\runbook-k3s-bootstrap.md` (🔴 OPEN 섹션 → 해소 기록)

**Interfaces:**
- Consumes: Task 3 배포 완료 상태. 기존 실측 스크립트(scratchpad `racetest.sh`, EC2 `/tmp/racetest.sh`에 업로드돼 있음).
- Produces: OPEN 이슈 종결 기록 + 사용자 브라우저 검증 결과.

- [ ] **Step 1: 동시성 실측 재실행**

```powershell
ssh -i $env:USERPROFILE\.ssh\devpath-k3s-key.pem ubuntu@13.124.153.105 'tr -d "\r" < /tmp/racetest.sh > /tmp/rt.sh && bash /tmp/rt.sh'
```

Expected: 순차 200→200, **동시 8라운드 전부 401 없음**(각 라운드 {200,200}), cleanup OK.

- [ ] **Step 2: 사용자 브라우저 E2E 검증 (사용자 게이트)**

사용자에게 요청: 시크릿 창에서 https://app.leva.ai.kr 로그인(GitHub) → 동의 화면 진행 → 제출 → 콘솔에 401 없음 + `/dashboard/me` 200 확인. 결과를 보고받아 기록한다. 실패 시 systematic-debugging Phase 1로 복귀(새 증거 수집).

- [ ] **Step 3: gitops 런북 OPEN 섹션 갱신**

```powershell
cd D:\workspace\dpa\devpath-gitops
git fetch origin; git switch -c docs/runbook-401-resolved origin/develop
```

`docs/runbook-k3s-bootstrap.md`의 `## 🔴 미해결 (OPEN)` 섹션을 해소 기록으로 교체: 근본 원인(동시 refresh × 비원자 단일-사용 회전 → 401 → 인터셉터 store.clear()), 기각 가설(PSL 등재는 사실이나 `*.ai.kr`이 아니므로 무해), 수정(platform 유예창 30s, PR 번호), 실측 결과(전후 비교), 남은 후속(frontend single-flight·reuse detection·BetaGate status·Redis 영속성). 트러블슈팅 표에도 1행 추가.

```powershell
git add docs/runbook-k3s-bootstrap.md
git commit -m @'
docs: 런북 OPEN(동의화면 401) 해소 기록 — refresh 회전 유예창

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
git push -u origin docs/runbook-401-resolved
gh pr create --base develop --title "docs: 런북 OPEN(동의화면 401) 해소 기록" --body @'
근본 원인·수정(platform 유예창)·운영 실측 전후 결과·후속 백로그 기록. 

🤖 Generated with [Claude Code](https://claude.com/claude-code)
'@
```

CI 녹색 확인 후 머지(`gh pr checks --watch` → `gh pr merge --merge`).

- [ ] **Step 4: 세션 메모리 갱신**

`C:\Users\deepe\.claude\projects\D--workspace-dpa\memory\devpath-ws-d-deploy.md`의 🔴 OPEN 섹션을 해소로 갱신(원인·수정 PR·검증 결과·후속 4건), `MEMORY.md` 훅 문구 갱신.
