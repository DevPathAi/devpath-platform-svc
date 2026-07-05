# ③ Google OAuth + 이메일 계정 통합 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans 또는 subagent-driven-development. 단계는 체크박스(`- [ ]`).

**Goal:** platform-svc에 Google OAuth 로그인을 추가하고, 같은 이메일의 GitHub/Google 로그인을 하나의 계정으로 통합한다(이메일 필수). web에 Google 로그인 버튼을 추가한다.

**Architecture:** 사용자 식별을 `token.getPrincipal().getName()`으로 일반화(GitHub=id/Google=sub). `registerOrFind`가 identity 없으면 이메일로 기존 User를 찾아 identity만 연결(통합), 없으면 신규. GitHub 비공개 이메일은 커스텀 `GithubEmailOAuth2UserService`가 `/user/emails`(primary+verified)로 보강. 이메일 없으면 로그인 거부.

**Tech Stack:** Java 21 · Spring Boot 4.0.7 · Spring Security 7 · RestClient · MockOAuth2Server(e2e)

## Global Constraints

- 브랜치: platform `feat/google-oauth`(develop 최신 #26 반영). 프론트는 별도 `feat/google-login-button`(devpath-frontend, develop 분기).
- **스키마 마이그레이션 없음** — `User.email`엔 `uq_users_email` UNIQUE 제약이 이미 있음(`V202606171001`). NULL 다중 허용.
- 이메일 **필수 + 통합**: 같은 email = 동일 `User`, identity만 provider별 다수. **verified 이메일만**(GitHub primary&&verified / Google email_verified).
- 식별: `providerUserId = token.getPrincipal().getName()`(Spring `CommonOAuth2Provider`의 user-name-attribute — GitHub=`id`, Google=`sub`).
- 이메일 확보 실패 → `MissingEmailException` → SuccessHandler가 `{webUrl}/login?error=email_required`로 리다이렉트(500 노출 금지).
- 검증: `registerOrFind`·`OAuth2LoginSuccessHandler`·e2e는 `@SpringBootTest`(로컬 도커 부재→**CI 검증**). 순수 로직만 로컬 단위.
- `OauthUser` record는 5-arg 유지(scope는 `registerOrFind` 내부 `scopeFor(provider)`로 산출 — 기존 테스트 시그니처 불변).

---

## File Structure

- Create `src/main/java/ai/devpath/platform/auth/MissingEmailException.java`
- Create `src/main/java/ai/devpath/platform/auth/GithubEmailOAuth2UserService.java`
- Modify `src/main/java/ai/devpath/platform/user/UserRepository.java` — `findByEmail`
- Modify `src/main/java/ai/devpath/platform/auth/UserRegistrationService.java` — `registerOrFind` 통합
- Modify `src/main/java/ai/devpath/platform/auth/OAuth2LoginSuccessHandler.java` — `getName()` + email 거부
- Modify `src/main/java/ai/devpath/platform/config/SecurityConfig.java` — `userInfoEndpoint().userService`
- Modify `src/main/resources/application.yml` — google registration
- Test Modify `src/test/java/ai/devpath/platform/auth/UserRegistrationServiceTest.java` — 통합·email거부
- Test Modify `src/test/java/ai/devpath/platform/auth/OAuthWebLoginE2ETest.java` — google + 통합
- (frontend, 별도 브랜치) Modify web `LoginPage` — Google 버튼

---

## Task 1: 이메일 통합 (UserRepository.findByEmail + registerOrFind)

**Files:**
- Modify: `UserRepository.java`
- Create: `MissingEmailException.java`
- Modify: `UserRegistrationService.java`
- Test: `UserRegistrationServiceTest.java`

**Interfaces — Produces:**
- `Optional<User> UserRepository.findByEmail(String email)`
- `MissingEmailException extends RuntimeException`
- `registerOrFind`: identity 있으면 그 User / 없고 email이 기존 User면 identity 연결 / 없으면 신규. email 없으면 `MissingEmailException`.

- [ ] **Step 1: UserRepository에 findByEmail 추가**

`UserRepository`의 인터페이스 본문(기존 `@Modifying` 쿼리 위)에 추가:

```java
  java.util.Optional<User> findByEmail(String email);
```

- [ ] **Step 2: MissingEmailException 작성**

Create `MissingEmailException.java`:

```java
package ai.devpath.platform.auth;

/** OAuth 로그인 시 이메일을 확보하지 못함 → 로그인 거부(이메일 필수). */
public class MissingEmailException extends RuntimeException {
  public MissingEmailException(String provider) {
    super("이메일을 확보하지 못했습니다: " + provider);
  }
}
```

- [ ] **Step 3: 실패 테스트 작성 — UserRegistrationServiceTest에 2개 추가**

`UserRegistrationServiceTest` 클래스 본문 끝(마지막 `}` 앞)에 추가. import에 `import static org.junit.jupiter.api.Assertions.assertThrows;` 추가:

```java
  @Test
  void emailMergeLinksIdentityToExistingUser() {
    long n = System.nanoTime();
    String email = "merge-" + n + "@example.com";
    User first = service.registerOrFind(
        new OauthUser("GITHUB", "gh-" + n, email, "지수", "t1"));
    long outboxAfterFirst = outbox.count();

    // 같은 이메일, 다른 provider(Google) → 동일 User에 identity만 연결
    User second = service.registerOrFind(
        new OauthUser("GOOGLE", "goog-" + n, email, "Jisoo", "t2"));

    assertEquals(first.getId(), second.getId(), "같은 이메일은 동일 계정");
    assertEquals(outboxAfterFirst, outbox.count(), "통합(기존 계정)은 가입 이벤트 미발생");
    assertTrue(identities.findByProviderAndProviderUserId("GOOGLE", "goog-" + n).isPresent(),
        "Google identity가 기존 User에 연결");
  }

  @Test
  void missingEmailIsRejected() {
    long n = System.nanoTime();
    assertThrows(MissingEmailException.class, () -> service.registerOrFind(
        new OauthUser("GITHUB", "gh-" + n, null, "지수", "t")));
  }
```

- [ ] **Step 4: 테스트 실패 확인**

Run: `./gradlew test --tests "ai.devpath.platform.auth.UserRegistrationServiceTest"` (로컬 도커 시) 또는 컴파일만 `./gradlew compileTestJava`
Expected: FAIL — `findByEmail`/`MissingEmailException`/통합 로직 미구현. (도커 부재 시 CI가 최종 검증)

- [ ] **Step 5: registerOrFind 개정**

`UserRegistrationService.registerOrFind`를 아래로 교체(기존 메서드 전체 치환). import는 그대로(User, UserOauthIdentity, UserProfile 등 이미 `ai.devpath.platform.user.*`):

```java
  @Transactional
  public User registerOrFind(OauthUser oauth) {
    var existing =
        identities.findByProviderAndProviderUserId(oauth.provider(), oauth.providerUserId());
    if (existing.isPresent()) {
      return users.findById(existing.get().getUserId()).orElseThrow();
    }

    if (oauth.email() == null || oauth.email().isBlank()) {
      throw new MissingEmailException(oauth.provider());
    }

    var byEmail = users.findByEmail(oauth.email());
    boolean isNew = byEmail.isEmpty();
    User user;
    if (byEmail.isPresent()) {
      user = byEmail.get(); // 이메일 통합: 기존 User에 identity만 추가
    } else {
      user = new User();
      user.setEmail(oauth.email());
      user.setNickname(oauth.nickname());
      user.setRole("LEARNER");
      user.setStatus("ACTIVE");
      user.setOnboardingStatus("PENDING");
      user = users.save(user);
      UserProfile profile = new UserProfile();
      profile.setUserId(user.getId());
      profiles.save(profile);
    }

    UserOauthIdentity identity = new UserOauthIdentity();
    identity.setUserId(user.getId());
    identity.setProvider(oauth.provider());
    identity.setProviderUserId(oauth.providerUserId());
    if (oauth.accessToken() != null) {
      identity.setAccessTokenEncrypted(cipher.encrypt(oauth.accessToken()));
    }
    identity.setScope(scopeFor(oauth.provider()));
    identity.setLinkedAt(Instant.now());
    identities.save(identity);

    if (isNew) {
      writeOutbox(user, oauth.provider());
    }
    return user;
  }

  private static String scopeFor(String provider) {
    return switch (provider) {
      case "GOOGLE" -> "openid,profile,email";
      default -> "read:user,user:email";
    };
  }
```

- [ ] **Step 6: 테스트 통과 확인 (로컬 도커 시) / 컴파일**

Run: `./gradlew compileTestJava` (그리고 도커 가용 시 `--tests "*UserRegistrationServiceTest"`)
Expected: 컴파일 성공. @SpringBootTest는 CI 검증.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/ai/devpath/platform/user/UserRepository.java src/main/java/ai/devpath/platform/auth/MissingEmailException.java src/main/java/ai/devpath/platform/auth/UserRegistrationService.java src/test/java/ai/devpath/platform/auth/UserRegistrationServiceTest.java
git commit -m "feat(auth): 이메일 기준 계정 통합 + findByEmail(이메일 필수)"
```

---

## Task 2: SuccessHandler — 식별 일반화 + 이메일 없음 거부

**Files:** Modify `OAuth2LoginSuccessHandler.java`

**Interfaces:**
- Consumes: `registration.registerOrFind`(Task 1, `MissingEmailException` 던짐)
- Produces: provider 무관 로그인(GitHub/Google), email 없으면 `/login?error=email_required`

- [ ] **Step 1: providerUserId 추출을 getName()으로 교체 + email 거부 처리**

`OAuth2LoginSuccessHandler.onAuthenticationSuccess`에서 두 곳 수정.

(a) `providerUserId` 추출 교체:

```java
		String providerUserId = token.getPrincipal().getName();
```
(기존 `String providerUserId = String.valueOf(attrs.get("id"));` 삭제)

(b) `registerOrFind` 호출을 try-catch로 감싸 email 없음 시 리다이렉트:

```java
		User user;
		try {
			user = registration.registerOrFind(
					new UserRegistrationService.OauthUser(provider, providerUserId, email, nickname, accessToken));
		} catch (MissingEmailException e) {
			response.sendRedirect(props.getWebUrl() + "/login?error=email_required");
			return;
		}
```
(기존 `var user = registration.registerOrFind(...);` 치환. `User` import는 `ai.devpath.platform.user.User` 추가.)

- [ ] **Step 2: 기존 테스트 회귀 확인**

Run: `./gradlew compileTestJava` (도커 시 `--tests "*OAuth2LoginSuccessHandlerTest" "*OAuth2LoginSuccessHandlerMobileTest"`)
Expected: 컴파일 성공. `getName()`은 `DefaultOAuth2User(attrs,"id")`에서 `attrs.get("id")`와 동일값이라 기존 테스트 불변(CI 검증).

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/ai/devpath/platform/auth/OAuth2LoginSuccessHandler.java
git commit -m "feat(auth): OAuth 사용자 식별을 provider 일반화(getName) + 이메일 없음 로그인 거부"
```

---

## Task 3: GithubEmailOAuth2UserService + SecurityConfig 배선

**Files:** Create `GithubEmailOAuth2UserService.java`; Modify `SecurityConfig.java`

**Interfaces — Produces:** `@Component GithubEmailOAuth2UserService extends DefaultOAuth2UserService` — GitHub이고 email 없으면 `/user/emails`에서 primary+verified 보강.

- [ ] **Step 1: GithubEmailOAuth2UserService 작성**

Create `GithubEmailOAuth2UserService.java`:

```java
package ai.devpath.platform.auth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** GitHub 로그인 시 userinfo에 email이 없으면 /user/emails에서 primary+verified를 보강한다. */
@Component
public class GithubEmailOAuth2UserService extends DefaultOAuth2UserService {

  private final RestClient rest = RestClient.create();

  @Override
  public OAuth2User loadUser(OAuth2UserRequest req) throws OAuth2AuthenticationException {
    OAuth2User user = super.loadUser(req);
    if (!"github".equals(req.getClientRegistration().getRegistrationId())) {
      return user;
    }
    if (user.getAttribute("email") != null) {
      return user;
    }
    String email = fetchPrimaryVerifiedEmail(req.getAccessToken().getTokenValue());
    if (email == null) {
      return user; // 이메일 미확보 → registerOrFind가 거부
    }
    Map<String, Object> attrs = new HashMap<>(user.getAttributes());
    attrs.put("email", email);
    String nameKey =
        req.getClientRegistration()
            .getProviderDetails()
            .getUserInfoEndpoint()
            .getUserNameAttributeName();
    return new DefaultOAuth2User(user.getAuthorities(), attrs, nameKey);
  }

  @SuppressWarnings("unchecked")
  private String fetchPrimaryVerifiedEmail(String accessToken) {
    List<Map<String, Object>> emails =
        rest.get()
            .uri("https://api.github.com/user/emails")
            .header("Authorization", "Bearer " + accessToken)
            .retrieve()
            .body(List.class);
    if (emails == null) {
      return null;
    }
    return emails.stream()
        .filter(e -> Boolean.TRUE.equals(e.get("primary")) && Boolean.TRUE.equals(e.get("verified")))
        .map(e -> (String) e.get("email"))
        .findFirst()
        .orElse(null);
  }
}
```

- [ ] **Step 2: SecurityConfig에 userService 배선**

`SecurityConfig.securityFilterChain` 시그니처에 `GithubEmailOAuth2UserService githubEmailService` 파라미터를 추가하고, `oauth2Login` 블록에 `userInfoEndpoint`를 추가:

```java
			.oauth2Login(oauth -> oauth
				.authorizationEndpoint(a -> a.authorizationRequestResolver(authorizationRequestResolver))
				.userInfoEndpoint(u -> u.userService(githubEmailService))
				.successHandler(successHandler))
```

> Google(OIDC)은 기본 `OidcUserService`가 처리(email/email_verified claim). `userService`는 비-OIDC(GitHub)에만 적용되므로 안전.

- [ ] **Step 3: 컴파일 확인 + 커밋**

Run: `./gradlew compileJava`
Expected: 성공. (UserService 보강 로직은 e2e/실서버에서 검증 — /user/emails 호출은 mock userinfo에 email이 있으면 타지 않음)

```bash
git add src/main/java/ai/devpath/platform/auth/GithubEmailOAuth2UserService.java src/main/java/ai/devpath/platform/config/SecurityConfig.java
git commit -m "feat(auth): GitHub 비공개 이메일 /user/emails 보강 UserService + SecurityConfig 배선"
```

---

## Task 4: Google registration (application.yml)

**Files:** Modify `application.yml`

- [ ] **Step 1: google registration 추가**

`spring.security.oauth2.client.registration` 아래 `github:` 형제로 `google:` 추가:

```yaml
          google:
            client-id: ${GOOGLE_CLIENT_ID:dummy-client-id}
            client-secret: ${GOOGLE_CLIENT_SECRET:dummy-secret}
            scope: openid,profile,email
```

- [ ] **Step 2: 커밋**

```bash
git add src/main/resources/application.yml
git commit -m "feat(auth): Google OAuth2 client registration"
```

---

## Task 5: e2e — Google 로그인 흐름 (OAuthWebLoginE2ETest 확장)

**Files:** Modify `OAuthWebLoginE2ETest.java`

**Interfaces:** 기존 `OAuthFlowDriver.run` 재사용(startPath만 `/oauth2/authorization/google`).

- [ ] **Step 1: google mock callback + provider 프로퍼티 추가**

`@BeforeAll startMock()`에 google OIDC callback 추가(기존 github enqueue 아래):

```java
		MOCK.enqueueCallback(new DefaultOAuth2TokenCallback(
				"google", "google-sub-" + UNIQUE_GH_ID, "JWT", List.of(),
				Map.of(
						"sub", "google-sub-" + UNIQUE_GH_ID,
						"name", "Google Tester",
						"email", "google@devpath.test",
						"email_verified", true),
				3600));
```

`@DynamicPropertySource oauthProps`에 google provider/registration 추가(기존 github 블록 아래, `web-url` 위):

```java
		String g = "google";
		r.add("spring.security.oauth2.client.provider.google.authorization-uri",
				() -> MOCK.authorizationEndpointUrl(g).toString());
		r.add("spring.security.oauth2.client.provider.google.token-uri",
				() -> MOCK.tokenEndpointUrl(g).toString());
		r.add("spring.security.oauth2.client.provider.google.user-info-uri",
				() -> MOCK.userInfoUrl(g).toString());
		r.add("spring.security.oauth2.client.provider.google.jwk-set-uri",
				() -> MOCK.jwksUrl(g).toString());
		r.add("spring.security.oauth2.client.provider.google.user-name-attribute", () -> "sub");
		r.add("spring.security.oauth2.client.registration.google.client-id", () -> "test-client");
		r.add("spring.security.oauth2.client.registration.google.client-secret", () -> "test-secret");
		r.add("spring.security.oauth2.client.registration.google.scope",
				() -> List.of("openid", "profile", "email"));
```

- [ ] **Step 2: google 로그인 e2e 테스트 추가**

`webLoginFlow_...` 테스트 아래에 추가:

```java
	@Test
	void googleWebLoginFlow_redirectsToCallback_setsRefreshCookie_andRefreshReturnsUser() {
		TestRestTemplate rest = noRedirectRestTemplate();
		var flow = OAuthFlowDriver.run(rest, port, "/oauth2/authorization/google", WEB_URL);

		assertThat(flow.callbackLocation()).isEqualTo(WEB_URL + "/auth/callback");
		assertThat(flow.refreshCookie()).isNotNull();
		assertThat(flow.refreshCookie().toLowerCase()).contains("httponly");

		HttpHeaders h = new HttpHeaders();
		h.add(HttpHeaders.COOKIE, flow.refreshCookiePair());
		h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
		ResponseEntity<Map> res = rest.exchange("http://localhost:" + port + "/auth/refresh",
				HttpMethod.POST, new HttpEntity<>(h), Map.class);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		@SuppressWarnings("unchecked")
		Map<String, Object> user = (Map<String, Object>) res.getBody().get("user");
		assertThat(String.valueOf(user.get("email"))).isEqualTo("google@devpath.test");
	}
```

- [ ] **Step 3: 컴파일 + 커밋 + 푸시 + PR + CI**

Run: `./gradlew compileTestJava`
Expected: 성공. e2e 실행은 CI(도커).

```bash
git add src/test/java/ai/devpath/platform/auth/OAuthWebLoginE2ETest.java
git commit -m "test(auth): Google OAuth 웹 로그인 e2e"
git push -u origin feat/google-oauth
gh pr create --base develop --title "feat(auth): ③ Google OAuth + 이메일 계정 통합" --body "spec/plan: docs/superpowers/{specs,plans}/2026-07-05-google-oauth*. Google registration + 식별 일반화(getName) + 이메일 통합(같은 email=동일 User) + GitHub /user/emails 보강 + google e2e. 스키마 무변경(email unique 기존). 프론트 버튼은 별도 PR."
```

Expected: PR CI(build, @SpringBootTest + e2e) 녹색.

---

## Task 6: frontend — web LoginPage Google 버튼 (별도 브랜치/PR)

**Files:** (devpath-frontend) Modify `apps/web/lib/src/features/auth/presentation/login_page.dart`

> **선행**: devpath-frontend develop에서 `feat/google-login-button` 분기. platform PR과 독립.

- [ ] **Step 1: LoginPage 실측 후 Google 버튼 추가**

`login_page.dart`를 열어 기존 "GitHub로 계속하기" 버튼 구현을 확인하고(핸들러가 `/oauth2/authorization/github`로 이동하는 방식), **동일 패턴으로** "Google로 계속하기" 버튼을 그 아래 추가한다. 실제 위젯/이동 방식은 기존 GitHub 버튼과 일치시킨다(추측 금지 — 파일 먼저 읽기).

- [ ] **Step 2: 분석/테스트 + 커밋 + PR**

Run: `cd D:\workspace\dpa\devpath-frontend && dart pub global run melos run analyze && dart pub global run melos run test && dart format .`
Expected: 그린 + format 정합(커밋 전 필수 — CI format 게이트).

```bash
git add apps/web/lib/src/features/auth/presentation/login_page.dart
git commit -m "feat(auth): web 로그인에 Google 버튼 추가"
git push -u origin feat/google-login-button
gh pr create --base develop --title "feat(auth): web Google 로그인 버튼" --body "platform ③ Google OAuth 후속. LoginPage에 '/oauth2/authorization/google' 버튼."
```

---

## Self-Review

**1. Spec coverage:** C1(GithubEmailOAuth2UserService)=Task3·C2(getName+email)=Task2·C3(통합+findByEmail)=Task1·C4(registration)=Task4·C5(e2e)=Task5·C6(프론트)=Task6. verified-only=Task3(primary&&verified)+e2e(email_verified). 이메일 필수 거부=Task1(MissingEmail)+Task2(리다이렉트). 커버 완료.

**2. Placeholder scan:** Task6 LoginPage만 "실측 후 동일 패턴"으로 위임(별도 레포 파일 미확인 — 추측 금지 원칙). platform Task1~5는 완전 코드.

**3. Type consistency:** `findByEmail`·`MissingEmailException`·`scopeFor`·`registerOrFind`·`getName()`·`OauthUser`(5-arg 유지) 일관. `OAuthFlowDriver.run`(기존)·`DefaultOAuth2TokenCallback`(기존 import) 재사용.

**리스크:** Google OIDC e2e에서 MockOAuth2Server가 id_token/jwks를 제공하는지(github 케이스가 jwk-set-uri를 이미 설정 → OIDC 지원 확인됨). `/user/emails` 보강은 mock userinfo에 email이 있으면 미실행이라 e2e에서 직접 검증 안 됨(실서버 런북).
