# 마이페이지 P2 — avatar 업로드/삭제 API 설계

- 날짜: 2026-07-05
- 레포: devpath-platform-svc
- 브랜치: `feat/mypage-avatar`
- 선행(충족): P1(#25 프로필 GET/PUT) 머지, shared 오브젝트 스토리지(#44) 발행
- 상태: 브레인스토밍 승인됨

## 배경 / 전제

- P1에서 `UserProfile`(avatar·bio·learningGoal·targetTrack·experienceYears) + `GET/PUT /users/me/profile` 구현·머지. `UserProfile.avatar`(VARCHAR512)는 조회/수정 대상이나 **파일 업로드 경로가 없다**.
- shared `ai.devpath.shared.storage` 발행분(확정 사실):
  - `ObjectStorage` 포트: `StoredObject put(key, byte[], contentType)` / `void delete(key)` / `String url(key)`
  - `S3ObjectStorage.url(key)` = `publicBaseUrl + "/" + bucket + "/" + key` — **역산 가능한 확정 공식**
  - `StoredFileValidator(allowedContentTypes, maxFileSize)`: `validate(contentType, size)` 위반 시 `IllegalArgumentException`, `key(prefix, filename)` = `<prefix>/<uuid>[.ext]`(확장자 화이트리스트 png/jpg/jpeg→jpg/webp)
  - `StorageProperties`(`devpath.storage.*`): endpoint/bucket/accessKey/secretKey/publicBaseUrl/maxFileSize(5MB)/allowedContentTypes(png·jpeg·webp)
  - `StorageAutoConfiguration`: `@ConditionalOnProperty("devpath.storage.endpoint")` — endpoint 설정된 svc에만 `S3Client`·`ObjectStorage` 빈 등록. **`StoredFileValidator`는 빈 미등록**(소비 svc가 정의)
  - `StorageException extends ApiException(ErrorCode.STORAGE_UNAVAILABLE=503)` → shared `ApiExceptionHandler`가 503 에러 envelope로 렌더. **platform은 이 핸들러를 이미 채택**(P1 `putRejects`의 400 VALIDATION_FAILED 통과가 증거)

## 목표

사용자가 자신의 프로필 아바타 이미지를 **업로드/삭제**할 수 있는 API를 platform-svc에 추가한다. 조회는 P1 `GET /profile`이 담당한다.

## API 계약

| 메서드 | 경로 | 요청 | 성공 | 실패 |
|--------|------|------|------|------|
| POST | `/users/me/avatar` | multipart `file` | 200 `ProfileView`(avatar=새 URL) | 400 VALIDATION_FAILED(형식/크기), 503 STORAGE_UNAVAILABLE |
| DELETE | `/users/me/avatar` | — | 200 `ProfileView`(avatar=null) | 503 STORAGE_UNAVAILABLE(삭제 장애) |

- 인증: 기존 JWT resource server(`anyRequest().authenticated()`), CSRF disabled.
- 인증 주체: `@AuthenticationPrincipal Jwt` → `Long.parseLong(jwt.getSubject())` (P1과 동일).
- 응답 타입은 P1 `ProfileView` 재사용(avatar 포함) — 프론트가 한 번의 응답으로 프로필 상태를 갱신.

## 데이터 & 흐름

- `UserProfile.avatar`에는 **완성된 공개 URL**을 저장한다 → P1 `GET /profile`이 그대로 반환하며 **조회 경로는 스토리지에 의존하지 않는다**(미가용이어도 프로필 조회 정상).
- 신규 `AvatarService`:
  - **업로드(userId, MultipartFile file)**:
    1. `validator.validate(file.getContentType(), file.getSize())` — 위반 → `IllegalArgumentException`(→400)
    2. `key = validator.key("avatars", file.getOriginalFilename())`
    3. `stored = storage.put(key, file.getBytes(), file.getContentType())` — 장애 → `StorageException`(→503)
    4. 기존 `profile.avatar`가 있으면 `storage.delete(keyOf(oldUrl))` **best-effort**(실패는 로그만; 신규 업로드는 이미 성공)
    5. `profile.avatar = stored.url()`; `save`; `ProfileView` 반환
  - **삭제(userId)**:
    1. `profile.avatar`가 있으면 `storage.delete(keyOf(url))` — 장애 → `StorageException`(→503, avatar 유지)
    2. `profile.avatar = null`; `save`; `ProfileView` 반환
  - **`keyOf(url)`**: `prefix = publicBaseUrl + "/" + bucket + "/"`; `url.startsWith(prefix)` 이면 `url.substring(prefix.length())`, 아니면 null(방어).
  - `profile` 없으면 P1과 동일하게 upsert(신규 `UserProfile`).

## 빈 배선 & 미가용

- `build.gradle.kts`: AWS SDK S3 `runtimeOnly`(shared와 **동일 버전** — shared는 compileOnly라 소비 svc가 런타임 제공).
- platform `StorageBeansConfig`(`@Configuration`, `@ConditionalOnProperty("devpath.storage.endpoint")`):
  - `@Bean StoredFileValidator`(props.getAllowedContentTypes(), props.getMaxFileSize())
- `AvatarService`는 `ObjectProvider<ObjectStorage>`·`ObjectProvider<StoredFileValidator>`로 주입 → `getIfAvailable() == null`이면 `StorageException`(503).
- 조회/기타 기능은 스토리지 미설정과 무관하게 동작.

## 설정

`application.yml`에 환경변수 주입:

```yaml
devpath:
  storage:
    endpoint: ${STORAGE_ENDPOINT:}
    bucket: ${STORAGE_BUCKET:devpath}
    access-key: ${STORAGE_ACCESS_KEY:}
    secret-key: ${STORAGE_SECRET_KEY:}
    public-base-url: ${STORAGE_PUBLIC_BASE_URL:http://localhost:9000}
```

- 로컬: shared `docker-compose`의 MinIO(:9000). 배포값은 gitops 소관.
- endpoint 미설정(로컬/CI 기본) → 스토리지 비활성 → 업로드/삭제만 503, 나머지 정상.

## 테스트 (P1 #25 교훈: 목으로 인프라 무의존)

- `AvatarControllerTest`(`@SpringBootTest`+`@AutoConfigureMockMvc`, `@MockBean ObjectStorage`, 실제 `StoredFileValidator` 빈 or 목):
  - 업로드 성공 → 200, `$.avatar` = 목이 반환한 url
  - 형식 위반(예: text/plain) → 400 VALIDATION_FAILED
  - 삭제 → 200, `$.avatar` doesNotExist/null
- `AvatarServiceTest`(단위): `ObjectProvider` 빈값 → 503; `keyOf` 역산 정확성.
- `@SpringBootTest`는 CI(도커: postgres 서비스 컨테이너 + embedded kafka)에서 검증.

## 영향 파일

- **신규**: `AvatarController`, `AvatarService`, `StorageBeansConfig`, `AvatarControllerTest`, `AvatarServiceTest`
- **수정**: `build.gradle.kts`(s3 runtimeOnly), `application.yml`(devpath.storage.*)
- **불변**: P1 `ProfileController`/`UserProfileService`/`ProfileView`/`UserProfile`(avatar 필드 재사용)

## 범위 밖

- 이미지 리사이즈/썸네일, CDN, 서명 URL
- avatar 외 파일 업로드
- 배포 환경 스토리지 설정(gitops 소관)
