# ③ Google OAuth + 이메일 계정 통합 설계

- 날짜: 2026-07-05
- 레포: devpath-platform-svc(백엔드 주도) + devpath-frontend(web 버튼)
- 브랜치: platform `feat/google-oauth`, frontend는 별도 `feat/google-login-button`
- 상태: 브레인스토밍 승인됨

## 배경 / 목표

기존 로그인은 GitHub OAuth만 지원. **Google OAuth 추가** + **이메일 기준 계정 통합**(같은 이메일의 GitHub/Google 로그인은 동일 계정).

**확정 사실(실측):**
- `User.email`은 nullable + **unique 제약 있음**(`uq_users_email`, `V202606171001__users_auth_extension.sql`) → **스키마 마이그레이션 불필요**. NULL은 Postgres에서 다중 허용(기존 email-null 유저 무방).
- `UserRepository`엔 `findByEmail` **없음** → 추가 필요.
- `UserRegistrationService.registerOrFind`는 `provider`+`providerUserId`로만 식별(이메일 병합 없음).
- `OAuth2LoginSuccessHandler`는 `providerUserId = attrs.get("id")`(**GitHub 전용**). Google(OIDC)은 식별자가 `sub`.
- `UserOauthIdentity`는 userId FK — 한 User에 provider별 identity 다수 저장 가능.
- 현행 GitHub registration scope = `read:user,user:email`(이미 user:email 보유).

## 결정 사항 (브레인스토밍)

- **스코프**: platform 백엔드 + web 프론트 "Google로 계속하기" 버튼.
- **이메일 필수**: OAuth 로그인 시 이메일이 없으면 **로그인 거부**.
- **계정 통합**: 같은 이메일 = 동일 `User`, identity만 provider별로 다수.
- **GitHub 비공개 이메일**: userinfo에 email이 없으면 GitHub `/user/emails`로 **primary+verified** 조회.
- **verified 이메일만 사용**(GitHub primary&&verified / Google email_verified) — 미검증 이메일 병합에 의한 계정 탈취 차단.
- **식별 일반화**: `providerUserId = token.getPrincipal().getName()`(GitHub=`id`, Google=`sub` 자동 — Spring `CommonOAuth2Provider`의 user-name-attribute).

## 컴포넌트

### C1. GitHub 이메일 보강 UserService (신규)
- `DefaultOAuth2UserService`를 확장한 `GithubEmailOAuth2UserService`. `registrationId == "github"`이고 userinfo에 email이 없으면 `OAuth2UserRequest`의 access token으로 GitHub `/user/emails` 호출 → `primary && verified`인 email을 attributes에 주입.
- Google(OIDC)은 Spring 기본 `OidcUserService`가 `email`+`email_verified` claim 제공 — 커스텀 불필요.
- `SecurityConfig`: `oauth2Login().userInfoEndpoint().userService(githubEmailService)`.

### C2. 사용자 식별 일반화 (OAuth2LoginSuccessHandler)
- `providerUserId = token.getPrincipal().getName()`(기존 `attrs.get("id")` 교체).
- `email = attrs.get("email")` — null이면 `registerOrFind`에서 거부.
- `nickname` 현행 유지(`name` ?? `login`; Google도 `name` 제공).

### C3. 계정 통합 (UserRegistrationService.registerOrFind 개정 + findByEmail)
- `UserRepository`에 `Optional<User> findByEmail(String email)` 추가.
- `registerOrFind` 개정:
  1. `identities.findByProviderAndProviderUserId(provider, providerUserId)` → 존재하면 그 User(재로그인).
  2. `email`이 null/blank → 로그인 거부 예외(이메일 필수).
  3. `users.findByEmail(email)`:
     - 존재 → 그 User에 **새 provider identity만 추가**(계정 통합, 신규 User/profile 미생성).
     - 없음 → 신규 `User` + identity + profile + outbox(UserRegisteredEvent).
- identity의 `scope`는 provider별 실제 값 저장(GitHub `read:user,user:email` / Google `openid,profile,email`) — OauthUser에 scope 전달하거나 provider별 상수. (기록용, 기능 무관 — 정확성 개선)

### C4. Google registration (application.yml)
```yaml
google:
  client-id: ${GOOGLE_CLIENT_ID:dummy-client-id}
  client-secret: ${GOOGLE_CLIENT_SECRET:dummy-secret}
  scope: openid,profile,email
```
Google은 Spring 기본 provider → provider URI 자동.

### C5. e2e (OAuthWebLoginE2ETest 확장)
- github(기존 유지) + **google(OIDC) 로그인** 케이스 추가(MockOAuth2Server가 `sub`/`email`/`name` claim, google provider URI+registration을 `@DynamicPropertySource`에).
- **이메일 통합 케이스**: 같은 email로 github→google 로그인 시 동일 `userId` 반환 검증(단위 테스트 우선, e2e 보조).

### C6. 프론트 (web LoginPage)
- "Google로 계속하기" 버튼 → `/oauth2/authorization/google` 이동. 기존 GitHub 버튼 옆.

## 데이터 흐름
```
GET /oauth2/authorization/{github|google} → provider authorize → callback
  → UserService(github면 email 보강) → SuccessHandler(getName=providerUserId, email)
  → registerOrFind(identity 조회 → 없으면 email 통합/신규)
  → (웹) refresh HttpOnly 쿠키 + {webUrl}/auth/callback 리다이렉트
  → (모바일) PKCE code 딥링크 (기존 흐름 불변)
```

## 에러 처리
- **이메일 없음**(GitHub 완전 비공개 + /user/emails에 verified primary 없음 — 드묾) → 로그인 거부: SuccessHandler가 예외를 잡아 `{webUrl}/login?error=email_required`로 리다이렉트(500 노출 금지).
- 나머지 OAuth 실패는 기존 Spring Security 흐름.

## 테스트
- **단위**: `registerOrFind`(신규·재로그인·**이메일 통합**·email 없음 거부), `GithubEmailOAuth2UserService`(/user/emails 보강 — RestClient 목), `OAuth2LoginSuccessHandler`(getName 기반 providerUserId).
- **e2e**: github·google 로그인 전 흐름 + 이메일 통합.

## 영향 파일
- **platform 신규**: `GithubEmailOAuth2UserService`, 단위테스트.
- **platform 수정**: `UserRepository`(findByEmail), `UserRegistrationService`(registerOrFind 개정), `OAuth2LoginSuccessHandler`(getName+email 검증), `SecurityConfig`(userService), `application.yml`(google), `OAuthWebLoginE2ETest`(google+통합).
- **frontend 수정**: web `LoginPage`(Google 버튼).
- **스키마**: 변경 없음(email unique 기존).

## 범위 밖
- 계정 연결 해제(unlink)·이메일 변경 시 재통합·3rd provider.
- email_verified=false Google 계정의 병합 정책(드묾 — 이번엔 verified만 통합, 미검증은 신규 취급하지 않고 거부).
