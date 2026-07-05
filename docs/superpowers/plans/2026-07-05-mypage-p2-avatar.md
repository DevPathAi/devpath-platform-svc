# 마이페이지 P2 — avatar 업로드/삭제 API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** platform-svc에 `POST/DELETE /users/me/avatar`를 추가해 사용자가 프로필 아바타 이미지를 업로드/삭제할 수 있게 한다.

**Architecture:** shared `ObjectStorage`/`StoredFileValidator`를 `ObjectProvider`로 주입하는 `AvatarService`(도메인 로직) + `AvatarController`(엔드포인트) + `StorageBeansConfig`(Validator 빈). `UserProfile.avatar`에는 완성된 공개 URL을 저장하고, 삭제 시 URL에서 key를 역산한다. 스토리지 미설정(로컬/CI 기본) 시 업로드/삭제만 503, 조회/기타는 무영향.

**Tech Stack:** Java 21 · Spring Boot 4.0.7 · AWS SDK v2 S3(MinIO 호환) · JUnit5 · Mockito

## Global Constraints

- 브랜치: `feat/mypage-avatar` (develop에서 분기 완료). 신규 작업은 이 브랜치에서만.
- shared 의존: `implementation("ai.devpath:devpath-shared:0.0.1-SNAPSHOT")` (이미 있음)
- AWS SDK: `software.amazon.awssdk:bom:2.28.0` + `s3` — platform은 **runtimeOnly**(shared는 compileOnly)
- 에러 렌더: `PlatformApplication`에 `@Import(ApiExceptionHandler.class)` 이미 존재 → `StorageException`→503(STORAGE_UNAVAILABLE), `IllegalArgumentException`→400(VALIDATION_FAILED). 응답 envelope는 `$.error.code`
- `UserProfile.avatar` = 완성된 공개 URL. `S3ObjectStorage.url(key)` = `publicBaseUrl + "/" + bucket + "/" + key`
- shared storage API: `ObjectStorage.put(key,byte[],contentType)→StoredObject(key,url)` / `delete(key)` / `url(key)`; `StoredFileValidator(allowedContentTypes,maxFileSize)`: `validate(contentType,size)`(위반 IllegalArgumentException), `key(prefix,filename)`
- P1(`ProfileController`/`UserProfileService`/`ProfileView`/`UserProfile`)은 **불변**. avatar 필드만 재사용
- 검증: 단위 테스트는 로컬 Mockito(도커 무의존), `@SpringBootTest` 통합은 CI(postgres 서비스 컨테이너 + embedded kafka)에서

---

## File Structure

- Create `src/main/java/ai/devpath/platform/user/AvatarService.java` — 업로드/삭제 도메인 로직, key 역산, 미가용 503
- Create `src/main/java/ai/devpath/platform/user/AvatarController.java` — `POST/DELETE /users/me/avatar`
- Create `src/main/java/ai/devpath/platform/config/StorageBeansConfig.java` — `StoredFileValidator` 빈(endpoint 조건부)
- Modify `build.gradle.kts` — AWS SDK S3 runtimeOnly
- Modify `src/main/resources/application.yml` — `devpath.storage.*`
- Test `src/test/java/ai/devpath/platform/config/StorageBeansConfigTest.java`
- Test `src/test/java/ai/devpath/platform/user/AvatarServiceTest.java`
- Test `src/test/java/ai/devpath/platform/user/AvatarControllerTest.java`

---

## Task 1: 의존성·설정·빈 배선

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/ai/devpath/platform/config/StorageBeansConfig.java`
- Test: `src/test/java/ai/devpath/platform/config/StorageBeansConfigTest.java`

**Interfaces:**
- Consumes: shared `StorageAutoConfiguration`(endpoint 설정 시 `S3Client`·`ObjectStorage`·`StorageProperties` 등록), `StorageProperties`, `StoredFileValidator`
- Produces: `StoredFileValidator` 빈(endpoint 설정 시). Task 2/3가 `ObjectProvider<StoredFileValidator>`로 소비

- [ ] **Step 1: build.gradle.kts에 S3 런타임 의존성 추가**

`dependencies { }` 블록의 `implementation("ai.devpath:devpath-shared:0.0.1-SNAPSHOT")` 아래에 추가:

```kotlin
	// P2 avatar: shared storage는 s3를 compileOnly로 제공 → 소비 svc가 런타임 s3 제공(MinIO 호환)
	runtimeOnly(platform("software.amazon.awssdk:bom:2.28.0"))
	runtimeOnly("software.amazon.awssdk:s3")
```

- [ ] **Step 2: application.yml에 storage 설정 추가**

`devpath:` 블록(파일 하단, `auth:` 형제)로 `storage:`를 추가:

```yaml
  storage:
    endpoint: ${STORAGE_ENDPOINT:}
    bucket: ${STORAGE_BUCKET:devpath}
    access-key: ${STORAGE_ACCESS_KEY:}
    secret-key: ${STORAGE_SECRET_KEY:}
    public-base-url: ${STORAGE_PUBLIC_BASE_URL:http://localhost:9000}
```

> 참고: `endpoint`는 로컬/CI에서 미설정(빈값) → shared `@ConditionalOnProperty("devpath.storage.endpoint")` 비활성 → 스토리지 빈 없음.

- [ ] **Step 3: 실패 테스트 작성 — StorageBeansConfigTest**

Create `src/test/java/ai/devpath/platform/config/StorageBeansConfigTest.java`:

```java
package ai.devpath.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.shared.storage.StorageAutoConfiguration;
import ai.devpath.shared.storage.StoredFileValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StorageBeansConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class))
          .withUserConfiguration(StorageBeansConfig.class);

  @Test
  void registersValidatorWhenEndpointSet() {
    runner
        .withPropertyValues(
            "devpath.storage.endpoint=http://minio:9000",
            "devpath.storage.bucket=devpath",
            "devpath.storage.access-key=k",
            "devpath.storage.secret-key=s",
            "devpath.storage.public-base-url=http://minio:9000")
        .run(ctx -> assertThat(ctx).hasSingleBean(StoredFileValidator.class));
  }

  @Test
  void noValidatorWhenEndpointMissing() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean(StoredFileValidator.class));
  }
}
```

- [ ] **Step 4: 테스트 실패 확인**

Run: `./gradlew test --tests "ai.devpath.platform.config.StorageBeansConfigTest"`
Expected: FAIL — `StorageBeansConfig` 클래스 없음(컴파일 에러)

- [ ] **Step 5: StorageBeansConfig 구현**

Create `src/main/java/ai/devpath/platform/config/StorageBeansConfig.java`:

```java
package ai.devpath.platform.config;

import ai.devpath.shared.storage.StorageProperties;
import ai.devpath.shared.storage.StoredFileValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** avatar 업로드 검증기. shared storage와 동일하게 endpoint 설정 시에만 등록한다. */
@Configuration
@ConditionalOnProperty("devpath.storage.endpoint")
public class StorageBeansConfig {

  @Bean
  public StoredFileValidator storedFileValidator(StorageProperties props) {
    return new StoredFileValidator(props.getAllowedContentTypes(), props.getMaxFileSize());
  }
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "ai.devpath.platform.config.StorageBeansConfigTest"`
Expected: PASS (2 tests)

> 만약 `registersValidatorWhenEndpointSet`에서 `StorageProperties` 빈을 못 찾으면, shared `StorageAutoConfiguration`의 `@EnableConfigurationProperties(StorageProperties.class)`가 러너에 등록되지 않은 것 — `AutoConfigurations.of(StorageAutoConfiguration.class)`가 이미 포함하므로 통과해야 한다. 실패 시 shared jar 최신 발행본을 받았는지(`./gradlew --refresh-dependencies`) 확인.

- [ ] **Step 7: 커밋**

```bash
git add build.gradle.kts src/main/resources/application.yml \
  src/main/java/ai/devpath/platform/config/StorageBeansConfig.java \
  src/test/java/ai/devpath/platform/config/StorageBeansConfigTest.java
git commit -m "feat(avatar): 스토리지 의존성·설정·StoredFileValidator 빈 배선"
```

---

## Task 2: AvatarService (도메인 로직, 단위 TDD)

**Files:**
- Create: `src/main/java/ai/devpath/platform/user/AvatarService.java`
- Test: `src/test/java/ai/devpath/platform/user/AvatarServiceTest.java`

**Interfaces:**
- Consumes: `ObjectStorage`, `StoredFileValidator`, `StorageProperties`(Task 1 배선), `UserProfileRepository`(P1), `ProfileView`(P1)
- Produces:
  - `ProfileView upload(long userId, byte[] content, String contentType, String filename)`
  - `ProfileView delete(long userId)`
  - `String keyOf(String url)` (package-private, 테스트/역산용)

- [ ] **Step 1: 실패 테스트 작성 — AvatarServiceTest**

Create `src/test/java/ai/devpath/platform/user/AvatarServiceTest.java`:

```java
package ai.devpath.platform.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.shared.storage.ObjectStorage;
import ai.devpath.shared.storage.ObjectStorage.StoredObject;
import ai.devpath.shared.storage.StorageException;
import ai.devpath.shared.storage.StorageProperties;
import ai.devpath.shared.storage.StoredFileValidator;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AvatarServiceTest {

  private ObjectStorage storage;
  private UserProfileRepository profiles;
  private StorageProperties props;
  private AvatarService service;

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> providerOf(T bean) {
    ObjectProvider<T> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(bean);
    return p;
  }

  @BeforeEach
  void setup() {
    storage = mock(ObjectStorage.class);
    profiles = mock(UserProfileRepository.class);
    StoredFileValidator validator =
        new StoredFileValidator(Set.of("image/png"), 5L * 1024 * 1024);
    props = new StorageProperties();
    props.setPublicBaseUrl("http://minio:9000");
    props.setBucket("devpath");
    service =
        new AvatarService(
            providerOf(storage), providerOf(validator), providerOf(props), profiles);
    when(profiles.save(any(UserProfile.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void uploadStoresUrlAndReturnsProfile() {
    when(profiles.findById(1L)).thenReturn(Optional.empty());
    when(storage.put(any(), any(), eq("image/png")))
        .thenReturn(new StoredObject("avatars/x.png", "http://minio:9000/devpath/avatars/x.png"));

    ProfileView view = service.upload(1L, new byte[] {1, 2, 3}, "image/png", "me.png");

    assertThat(view.avatar()).isEqualTo("http://minio:9000/devpath/avatars/x.png");
    verify(storage).put(any(), any(), eq("image/png"));
  }

  @Test
  void uploadRejectsDisallowedType() {
    when(profiles.findById(1L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.upload(1L, new byte[] {1}, "text/plain", "x.txt"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void uploadThrows503WhenStorageMissing() {
    AvatarService noStorage =
        new AvatarService(providerOf(null), providerOf(null), providerOf(props), profiles);
    assertThatThrownBy(() -> noStorage.upload(1L, new byte[] {1}, "image/png", "x.png"))
        .isInstanceOf(StorageException.class);
  }

  @Test
  void deleteRemovesObjectAndNullsAvatar() {
    UserProfile p = new UserProfile();
    p.setUserId(1L);
    p.setAvatar("http://minio:9000/devpath/avatars/old.png");
    when(profiles.findById(1L)).thenReturn(Optional.of(p));

    ProfileView view = service.delete(1L);

    verify(storage).delete("avatars/old.png");
    assertThat(view.avatar()).isNull();
  }

  @Test
  void keyOfExtractsKeyFromUrl() {
    assertThat(service.keyOf("http://minio:9000/devpath/avatars/a.png"))
        .isEqualTo("avatars/a.png");
    assertThat(service.keyOf("http://other/x")).isNull();
    assertThat(service.keyOf(null)).isNull();
  }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "ai.devpath.platform.user.AvatarServiceTest"`
Expected: FAIL — `AvatarService` 없음(컴파일 에러)

- [ ] **Step 3: AvatarService 구현**

Create `src/main/java/ai/devpath/platform/user/AvatarService.java`:

```java
package ai.devpath.platform.user;

import ai.devpath.platform.user.dto.ProfileView;
import ai.devpath.shared.storage.ObjectStorage;
import ai.devpath.shared.storage.ObjectStorage.StoredObject;
import ai.devpath.shared.storage.StorageException;
import ai.devpath.shared.storage.StorageProperties;
import ai.devpath.shared.storage.StoredFileValidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 프로필 아바타 업로드/삭제. 스토리지 미구성 시 503(STORAGE_UNAVAILABLE). */
@Service
public class AvatarService {

  private final ObjectProvider<ObjectStorage> storageProvider;
  private final ObjectProvider<StoredFileValidator> validatorProvider;
  private final ObjectProvider<StorageProperties> propsProvider;
  private final UserProfileRepository profiles;

  public AvatarService(
      ObjectProvider<ObjectStorage> storageProvider,
      ObjectProvider<StoredFileValidator> validatorProvider,
      ObjectProvider<StorageProperties> propsProvider,
      UserProfileRepository profiles) {
    this.storageProvider = storageProvider;
    this.validatorProvider = validatorProvider;
    this.propsProvider = propsProvider;
    this.profiles = profiles;
  }

  @Transactional
  public ProfileView upload(long userId, byte[] content, String contentType, String filename) {
    ObjectStorage storage = storage();
    validator().validate(contentType, content.length);
    String key = validator().key("avatars", filename);
    StoredObject stored = storage.put(key, content, contentType);
    UserProfile p = profiles.findById(userId).orElseGet(() -> newProfile(userId));
    deleteQuietly(storage, keyOf(p.getAvatar())); // 기존 교체 best-effort
    p.setAvatar(stored.url());
    return ProfileView.of(profiles.save(p));
  }

  @Transactional
  public ProfileView delete(long userId) {
    ObjectStorage storage = storage();
    UserProfile p = profiles.findById(userId).orElseGet(() -> newProfile(userId));
    String key = keyOf(p.getAvatar());
    if (key != null) {
      storage.delete(key); // 삭제 장애 → StorageException(503), avatar 유지
    }
    p.setAvatar(null);
    return ProfileView.of(profiles.save(p));
  }

  String keyOf(String url) {
    if (url == null) {
      return null;
    }
    StorageProperties props = propsProvider.getIfAvailable();
    if (props == null) {
      return null;
    }
    String prefix = props.getPublicBaseUrl() + "/" + props.getBucket() + "/";
    return url.startsWith(prefix) ? url.substring(prefix.length()) : null;
  }

  private ObjectStorage storage() {
    ObjectStorage s = storageProvider.getIfAvailable();
    if (s == null) {
      throw new StorageException("스토리지가 구성되지 않았습니다");
    }
    return s;
  }

  private StoredFileValidator validator() {
    StoredFileValidator v = validatorProvider.getIfAvailable();
    if (v == null) {
      throw new StorageException("스토리지가 구성되지 않았습니다");
    }
    return v;
  }

  private void deleteQuietly(ObjectStorage storage, String key) {
    if (key == null) {
      return;
    }
    try {
      storage.delete(key);
    } catch (RuntimeException ignored) {
      // best-effort: 이전 객체 정리 실패는 무시(고아 객체는 후속 정리)
    }
  }

  private UserProfile newProfile(long userId) {
    UserProfile np = new UserProfile();
    np.setUserId(userId);
    return np;
  }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "ai.devpath.platform.user.AvatarServiceTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/ai/devpath/platform/user/AvatarService.java \
  src/test/java/ai/devpath/platform/user/AvatarServiceTest.java
git commit -m "feat(avatar): AvatarService 업로드/삭제 도메인 로직 + 단위 테스트"
```

---

## Task 3: AvatarController (엔드포인트, 통합 TDD)

**Files:**
- Create: `src/main/java/ai/devpath/platform/user/AvatarController.java`
- Test: `src/test/java/ai/devpath/platform/user/AvatarControllerTest.java`

**Interfaces:**
- Consumes: `AvatarService.upload/delete`(Task 2), `JwtService`(P1 테스트에서 사용), `UserRepository`(P1)
- Produces: `POST/DELETE /users/me/avatar`

- [ ] **Step 1: 실패 테스트 작성 — AvatarControllerTest**

Create `src/test/java/ai/devpath/platform/user/AvatarControllerTest.java`:

```java
package ai.devpath.platform.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.platform.auth.jwt.JwtService;
import ai.devpath.shared.storage.ObjectStorage;
import ai.devpath.shared.storage.ObjectStorage.StoredObject;
import ai.devpath.shared.storage.StorageProperties;
import ai.devpath.shared.storage.StoredFileValidator;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AvatarControllerTest.TestStorageConfig.class)
class AvatarControllerTest {

  @Autowired MockMvc mvc;
  @Autowired JwtService jwt;
  @Autowired UserRepository users;
  @MockBean ObjectStorage storage;

  @TestConfiguration
  static class TestStorageConfig {
    @Bean
    StoredFileValidator storedFileValidator() {
      return new StoredFileValidator(
          Set.of("image/png", "image/jpeg", "image/webp"), 5L * 1024 * 1024);
    }

    @Bean
    StorageProperties storageProperties() {
      StorageProperties p = new StorageProperties();
      p.setPublicBaseUrl("http://minio:9000");
      p.setBucket("devpath");
      return p;
    }
  }

  private User newUser() {
    User u = new User();
    u.setEmail("avatar-" + System.nanoTime() + "@example.com");
    u.setNickname("아바타유저");
    u.setRole("LEARNER");
    u.setStatus("ACTIVE");
    u.setOnboardingStatus("PENDING");
    return users.save(u);
  }

  @Test
  void uploadReturnsAvatarUrl() throws Exception {
    User u = newUser();
    String token = jwt.mintAccessToken(u.getId(), "LEARNER");
    when(storage.put(any(), any(), any()))
        .thenReturn(new StoredObject("avatars/x.png", "http://minio:9000/devpath/avatars/x.png"));
    MockMultipartFile file =
        new MockMultipartFile("file", "me.png", MediaType.IMAGE_PNG_VALUE, new byte[] {1, 2, 3});

    mvc.perform(multipart("/users/me/avatar").file(file).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.avatar").value("http://minio:9000/devpath/avatars/x.png"));
  }

  @Test
  void uploadRejectsNonImageWith400() throws Exception {
    User u = newUser();
    String token = jwt.mintAccessToken(u.getId(), "LEARNER");
    MockMultipartFile file =
        new MockMultipartFile("file", "x.txt", MediaType.TEXT_PLAIN_VALUE, new byte[] {1});

    mvc.perform(multipart("/users/me/avatar").file(file).header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
  }

  @Test
  void deleteNullsAvatar() throws Exception {
    User u = newUser();
    String token = jwt.mintAccessToken(u.getId(), "LEARNER");
    mvc.perform(delete("/users/me/avatar").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.avatar").doesNotExist());
  }
}
```

> `multipart(...)`는 기본 POST. `MockMultipartFile`의 파라미터명은 컨트롤러 `@RequestParam("file")`과 일치해야 한다.
> `@MockBean ObjectStorage`는 shared autoconfig가 비활성(test에 endpoint 미설정)이라 새 목 빈으로 등록된다. `StoredFileValidator`/`StorageProperties`는 `TestStorageConfig`가 제공.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "ai.devpath.platform.user.AvatarControllerTest"`
Expected: FAIL — `AvatarController` 없음(컴파일 에러). (로컬 도커 부재 시 컴파일 단계에서 실패 확인)

- [ ] **Step 3: AvatarController 구현**

Create `src/main/java/ai/devpath/platform/user/AvatarController.java`:

```java
package ai.devpath.platform.user;

import ai.devpath.platform.user.dto.ProfileView;
import java.io.IOException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/users/me/avatar")
public class AvatarController {

  private final AvatarService service;

  public AvatarController(AvatarService service) {
    this.service = service;
  }

  @PostMapping
  public ProfileView upload(
      @AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file)
      throws IOException {
    return service.upload(
        Long.parseLong(jwt.getSubject()),
        file.getBytes(),
        file.getContentType(),
        file.getOriginalFilename());
  }

  @DeleteMapping
  public ProfileView delete(@AuthenticationPrincipal Jwt jwt) {
    return service.delete(Long.parseLong(jwt.getSubject()));
  }
}
```

- [ ] **Step 4: 로컬 컴파일 + 단위 테스트 재확인**

Run: `./gradlew compileJava compileTestJava` (그리고 도커 가용 시 `./gradlew test --tests "ai.devpath.platform.user.AvatarServiceTest"`)
Expected: 컴파일 성공, 단위 테스트 PASS. `@SpringBootTest`(AvatarControllerTest)는 CI에서 검증.

- [ ] **Step 5: 커밋 + 푸시 + PR 생성 + CI 검증**

```bash
git add src/main/java/ai/devpath/platform/user/AvatarController.java \
  src/test/java/ai/devpath/platform/user/AvatarControllerTest.java
git commit -m "feat(avatar): POST/DELETE /users/me/avatar 컨트롤러 + 통합 테스트"
git push -u origin feat/mypage-avatar
gh pr create --base develop --title "feat(avatar): 마이페이지 P2 — avatar 업로드/삭제" --body "spec/plan: docs/superpowers/{specs,plans}/2026-07-05-mypage-p2-avatar*. POST/DELETE /users/me/avatar, shared storage 배선, 미가용 503."
```

Expected: PR CI(build) 녹색. `AvatarControllerTest`가 CI에서 통과. 녹색 확인 후에만 머지 대상.

---

## Self-Review

**1. Spec coverage:**
- API 계약(POST/DELETE, 200/400/503) → Task 3(컨트롤러 테스트: 200 upload, 400 invalid, 200 delete) + Task 2(503 단위)
- avatar=공개 URL 저장, keyOf 역산 → Task 2(uploadStoresUrl, keyOf 테스트)
- 기존 교체 best-effort / DELETE 503 → Task 2 구현(deleteQuietly vs storage.delete 직접)
- 빈 배선(S3 runtime, Validator 빈, ObjectProvider) → Task 1 + Task 2 생성자
- 설정(devpath.storage.*) → Task 1 Step 2
- 미가용(503) → Task 2 uploadThrows503
- 커버리지 갭 없음.

**2. Placeholder scan:** 코드 블록 전부 실제 구현. "TBD/TODO/적절히 처리" 없음.

**3. Type consistency:**
- `AvatarService.upload(long, byte[], String, String)` / `delete(long)` / `keyOf(String)` — Task 2 정의와 Task 3 컨트롤러 호출 일치.
- `ObjectStorage.StoredObject(key, url)`, `ProfileView.of(p)`, `ProfileView.avatar()` — shared/P1 실제 시그니처와 일치.
- `StoredFileValidator(Set, long)` 생성자 — shared 실제와 일치.

이슈 없음.
