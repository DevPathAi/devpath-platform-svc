# 마이페이지 P1 — platform 프로필 API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`).
> **Phase P1 of** [2026-07-05-mypage-design.md](../../../../devpath-frontend/docs/superpowers/specs/2026-07-05-mypage-design.md). 스토리지 무관·선행 가능. 후속: P2 avatar(발행 후)·P3 집계·P4 frontend는 별도 plan.

**Goal:** platform에 `GET/PUT /users/me/profile`을 추가해 마이페이지가 사용자 프로필(자기소개·학습목표·목표트랙·경력)을 조회/편집할 수 있게 한다.

**Architecture:** 기존 `UserProfile` 엔티티·`UserProfileRepository`(JpaRepository) 위에 `UserProfileService`(조회 기본값 + upsert + 검증)와 `ProfileController`를 추가한다. avatar는 이 API로 바꾸지 않는다(P2 별도).

**Tech Stack:** Spring Boot 4.0.7 · Java 21 · JPA · Gradle.

## Global Constraints

- **컨트롤러 인증 패턴**: `@AuthenticationPrincipal Jwt jwt` → `Long.parseLong(jwt.getSubject())`(ConsentController·UserController와 동일).
- **검증 위반 = `IllegalArgumentException`** → shared `ApiExceptionHandler`가 400 VALIDATION_FAILED로 렌더(platform이 `@Import(ApiExceptionHandler)` 채택 완료, ④에서 확인). 새 핸들러 불필요.
- **검증 규칙**: `bio` ≤ 500자, `experienceYears`는 null 또는 0~50.
- **avatar 불변**: `PUT /users/me/profile`는 avatar를 건드리지 않는다(P2 업로드 전용).
- **테스트 패턴**: `ConsentControllerTest` 복제 — `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`, `@Autowired MockMvc/JwtService/UserRepository`, `newUser()` 헬퍼(email에 `System.nanoTime()`로 고유화 — 로컬 devpath DB 오염 회피), `jwt.mintAccessToken(userId, "LEARNER")`.
- **DB 의존**: `@SpringBootTest`는 로컬 postgres(docker-compose devpath) 필요. 로컬 도커 부재 시 로컬 실행 불가 → **CI(도커 가용)에서 검증**.
- 모든 명령은 `cd /d/workspace/dpa/devpath-platform-svc`. 브랜치 `feat/mypage-profile`(이미 생성, base develop).

## File Structure

- `src/main/java/ai/devpath/platform/user/dto/ProfileView.java` (신규): 응답 DTO
- `src/main/java/ai/devpath/platform/user/dto/ProfileUpdateRequest.java` (신규): PUT body
- `src/main/java/ai/devpath/platform/user/UserProfileService.java` (신규): 조회 기본값 + upsert + 검증
- `src/main/java/ai/devpath/platform/user/ProfileController.java` (신규): GET/PUT
- `src/test/java/ai/devpath/platform/user/ProfileControllerTest.java` (신규): 통합 테스트
- 기존 `UserProfile`·`UserProfileRepository` 재사용(수정 없음)

---

## Task 1: 프로필 도메인 (DTO + Service)

**Files:**
- Create: `src/main/java/ai/devpath/platform/user/dto/ProfileView.java`
- Create: `src/main/java/ai/devpath/platform/user/dto/ProfileUpdateRequest.java`
- Create: `src/main/java/ai/devpath/platform/user/UserProfileService.java`

**Interfaces:**
- Produces: `ProfileView(String avatar, String bio, String learningGoal, String targetTrack, Integer experienceYears)` + `static of(UserProfile)`; `ProfileUpdateRequest(String bio, String learningGoal, String targetTrack, Integer experienceYears)`; `UserProfileService.get(long userId) → ProfileView`(없으면 전 필드 null), `update(long userId, ProfileUpdateRequest) → ProfileView`(검증 후 upsert, avatar 보존).

- [ ] **Step 1: ProfileView + ProfileUpdateRequest 작성**

`src/main/java/ai/devpath/platform/user/dto/ProfileView.java`:
```java
package ai.devpath.platform.user.dto;

import ai.devpath.platform.user.UserProfile;

public record ProfileView(
    String avatar, String bio, String learningGoal, String targetTrack, Integer experienceYears) {
  public static ProfileView of(UserProfile p) {
    return new ProfileView(
        p.getAvatar(), p.getBio(), p.getLearningGoal(), p.getTargetTrack(), p.getExperienceYears());
  }
}
```

`src/main/java/ai/devpath/platform/user/dto/ProfileUpdateRequest.java`:
```java
package ai.devpath.platform.user.dto;

public record ProfileUpdateRequest(
    String bio, String learningGoal, String targetTrack, Integer experienceYears) {}
```

- [ ] **Step 2: UserProfileService 작성**

`src/main/java/ai/devpath/platform/user/UserProfileService.java`:
```java
package ai.devpath.platform.user;

import ai.devpath.platform.user.dto.ProfileUpdateRequest;
import ai.devpath.platform.user.dto.ProfileView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {

  private static final int MAX_BIO = 500;

  private final UserProfileRepository profiles;

  public UserProfileService(UserProfileRepository profiles) {
    this.profiles = profiles;
  }

  @Transactional(readOnly = true)
  public ProfileView get(long userId) {
    return profiles
        .findById(userId)
        .map(ProfileView::of)
        .orElseGet(() -> new ProfileView(null, null, null, null, null));
  }

  @Transactional
  public ProfileView update(long userId, ProfileUpdateRequest req) {
    validate(req);
    UserProfile p =
        profiles
            .findById(userId)
            .orElseGet(
                () -> {
                  UserProfile np = new UserProfile();
                  np.setUserId(userId);
                  return np;
                });
    p.setBio(req.bio());
    p.setLearningGoal(req.learningGoal());
    p.setTargetTrack(req.targetTrack());
    p.setExperienceYears(req.experienceYears());
    return ProfileView.of(profiles.save(p));
  }

  private void validate(ProfileUpdateRequest req) {
    if (req.bio() != null && req.bio().length() > MAX_BIO) {
      throw new IllegalArgumentException("자기소개는 " + MAX_BIO + "자 이하여야 합니다");
    }
    if (req.experienceYears() != null && (req.experienceYears() < 0 || req.experienceYears() > 50)) {
      throw new IllegalArgumentException("경력 연차가 유효 범위(0~50)를 벗어났습니다");
    }
  }
}
```

- [ ] **Step 3: 컴파일 확인 + 커밋**

Run: `./gradlew compileJava --console=plain 2>&1 | tail -4`
Expected: `BUILD SUCCESSFUL`.
```bash
git add src/main/java/ai/devpath/platform/user/dto/ProfileView.java src/main/java/ai/devpath/platform/user/dto/ProfileUpdateRequest.java src/main/java/ai/devpath/platform/user/UserProfileService.java
git commit -m "feat(profile): ProfileView/ProfileUpdateRequest + UserProfileService(조회 기본값+upsert+검증)"
```

---

## Task 2: ProfileController + 통합 테스트

**Files:**
- Create: `src/main/java/ai/devpath/platform/user/ProfileController.java`
- Test: `src/test/java/ai/devpath/platform/user/ProfileControllerTest.java`

**Interfaces:**
- Consumes: `UserProfileService`(Task 1).
- Produces: `GET /users/me/profile` → `ProfileView`; `PUT /users/me/profile`(body `ProfileUpdateRequest`) → `ProfileView`.

- [ ] **Step 1: 실패 통합 테스트 작성**

`src/test/java/ai/devpath/platform/user/ProfileControllerTest.java`:
```java
package ai.devpath.platform.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.platform.auth.jwt.JwtService;
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
class ProfileControllerTest {

  @Autowired MockMvc mvc;
  @Autowired JwtService jwt;
  @Autowired UserRepository users;

  private User newUser() {
    User u = new User();
    u.setEmail("profile-" + System.nanoTime() + "@example.com");
    u.setNickname("프로필유저");
    u.setRole("LEARNER");
    u.setStatus("ACTIVE");
    u.setOnboardingStatus("PENDING");
    return users.save(u);
  }

  @Test
  void getReturnsEmptyProfileWhenNoneSaved() throws Exception {
    User u = newUser();
    String token = jwt.mintAccessToken(u.getId(), "LEARNER");
    mvc.perform(get("/users/me/profile").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bio").doesNotExist());
  }

  @Test
  void putSavesAndReturnsProfile() throws Exception {
    User u = newUser();
    String token = jwt.mintAccessToken(u.getId(), "LEARNER");
    mvc.perform(
            put("/users/me/profile")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"bio\":\"백엔드 지망\",\"learningGoal\":\"취업\",\"targetTrack\":\"BACKEND\",\"experienceYears\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bio").value("백엔드 지망"))
        .andExpect(jsonPath("$.experienceYears").value(2));

    mvc.perform(get("/users/me/profile").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetTrack").value("BACKEND"));
  }

  @Test
  void putRejectsInvalidExperienceYearsWith400() throws Exception {
    User u = newUser();
    String token = jwt.mintAccessToken(u.getId(), "LEARNER");
    mvc.perform(
            put("/users/me/profile")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"experienceYears\":99}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*ProfileControllerTest' --console=plain 2>&1 | tail -8`
Expected: 컴파일 실패(`ProfileController` 미존재). (도커 postgres 필요 — 로컬 부재 시 컨텍스트 로드 단계 실패로도 확인 가능.)

- [ ] **Step 3: ProfileController 구현**

`src/main/java/ai/devpath/platform/user/ProfileController.java`:
```java
package ai.devpath.platform.user;

import ai.devpath.platform.user.dto.ProfileUpdateRequest;
import ai.devpath.platform.user.dto.ProfileView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/me/profile")
public class ProfileController {

  private final UserProfileService service;

  public ProfileController(UserProfileService service) {
    this.service = service;
  }

  @GetMapping
  public ProfileView get(@AuthenticationPrincipal Jwt jwt) {
    return service.get(Long.parseLong(jwt.getSubject()));
  }

  @PutMapping
  public ProfileView update(
      @AuthenticationPrincipal Jwt jwt, @RequestBody ProfileUpdateRequest body) {
    return service.update(Long.parseLong(jwt.getSubject()), body);
  }
}
```

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `./gradlew test --tests '*ProfileControllerTest' --console=plain 2>&1 | tail -8`
Expected: PASS(도커 postgres 필요). 로컬 도커 부재면 컨텍스트 로드 실패 — Task 3의 `build -x test` + CI 검증으로 대체.
```bash
git add src/main/java/ai/devpath/platform/user/ProfileController.java src/test/java/ai/devpath/platform/user/ProfileControllerTest.java
git commit -m "feat(profile): GET/PUT /users/me/profile 컨트롤러 + 통합 테스트"
```

---

## Task 3: 빌드 + PR

- [ ] **Step 1: 빌드 확인**

Run: `./gradlew build --console=plain 2>&1 | tail -6`
Expected: `BUILD SUCCESSFUL`. 로컬 도커 부재로 `@SpringBootTest`가 postgres 연결 실패 시 `./gradlew build -x test`로 컴파일·패키징 확인 + CI에서 test 검증(ProfileControllerTest 포함).

- [ ] **Step 2: develop PR**

```bash
git push -u origin feat/mypage-profile
gh pr create --base develop --head feat/mypage-profile \
  --title "feat(profile): 마이페이지 P1 — GET/PUT /users/me/profile" \
  --body "스펙: devpath-frontend/docs/superpowers/specs/2026-07-05-mypage-design.md (P1). UserProfileService(조회 기본값+upsert+검증 bio≤500·experienceYears 0~50) + ProfileController. avatar는 P2 별도. 로컬 도커 부재 시 @SpringBootTest는 CI 검증."
```

---

## Self-Review 결과

- **Spec 커버리지(P1)**: GET/PUT profile→Task2, ProfileView/Service→Task1, 검증(bio·experienceYears)→Task1·2. avatar 불변·P2 게이팅 반영. ✅
- **플레이스홀더**: 실코드·실명령. 없음. ✅
- **타입 일관**: `ProfileView`(avatar·bio·learningGoal·targetTrack·experienceYears) — Task1 정의, Task2 반환·검증 일치. `ProfileUpdateRequest`(avatar 없음 — 불변 규칙) 일관. ✅
- **주의**: `@SpringBootTest`는 로컬 postgres(도커) 필요 — 로컬 부재 시 CI 검증(스토리지 IT와 동일 제약). Task3에 `build -x test` 대체 명시.
- **범위**: P1(platform 프로필)만. P2/P3/P4는 별도 plan(후속). ✅
