# 모바일 인증 보안 하드닝 설계 (A/B/C)

> 상태: **설계 문서(구현 보류)**. 현재 구현(devpath-platform-svc PR #14, devpath-frontend PR #49) 위에서의 후속 하드닝. 합의 후 트랙별로 TDD 구현 착수.
> file:line 참조는 PR #14/#49 머지 후 기준.

## 0. 현재 상태 요약 (코드 근거)

OAuth는 백엔드가 GitHub와 서버사이드로 중개한다(앱은 code 교환을 하지 않음).

- 모바일 시작: `auth_controller.dart` `login()` → `{base}/oauth2/authorization/github?client_type=mobile` 외부 브라우저 오픈.
- 모바일 식별: `MobileAwareAuthorizationRequestResolver`(state suffix `.mobile`).
- 성공 처리: `OAuth2LoginSuccessHandler` → 모바일이면 access 즉시 mint + `devpath://callback#access_token=…&refresh_token=…`(fragment) 리다이렉트, 쿠키 미설정.
- 콜백 파싱: `auth_callback.dart` `parseAuthCallback` → fragment/query에서 `access_token`+`refresh_token` 추출.
- refresh: `api_providers.dart`(바디 `{refresh_token}`) ↔ `AuthController.refresh`(바디 폴백 + 회전 토큰 바디 반환).

### 약점
1. **토큰이 리다이렉트 URL에 노출**(fragment). 단말 로그/히스토리/악성 핸들러 노출 가능.
2. **custom scheme `devpath://callback`은 OS 소유권 검증이 없어** 동일 스킴을 등록한 악성 앱이 콜백을 가로채 access+refresh를 통째로 탈취 가능(RFC 8252 안티패턴).
3. **PKCE 부재** — 앱 경계에서 가로채기 방지 장치 없음(현재 "토큰 자체"가 리다이렉트로 오므로 가로채면 즉시 유효).

---

## 트랙 A — 일회용 코드 + PKCE 토큰 교환 (최우선)

**목표**: 리다이렉트에서 토큰을 제거하고, 가로채도 무용지물이 되게 한다.

### 새 플로우
```
앱: code_verifier 생성 → code_challenge = BASE64URL(SHA256(verifier))
앱 → 브라우저: /oauth2/authorization/github?client_type=mobile
                 &code_challenge=<cc>&code_challenge_method=S256
GitHub 인증 → 백엔드 success handler:
   - 1회용 auth code 발급(랜덤), Redis 저장: code → {userId, codeChallenge}, TTL 60s, 1회성
   - 리다이렉트: devpath://callback?code=<auth_code>     ← 토큰 없음
앱: 딥링크에서 code 추출 → POST /auth/oauth/token {code, code_verifier}
백엔드: SHA256(verifier)==저장된 challenge 검증 + code 소비(삭제) →
        access mint + refresh issue → 바디로 반환
앱: 토큰 저장 → bootstrapSession()
```

### 백엔드 변경 (devpath-platform-svc)
1. **PKCE 챌린지 라운드트립**: `MobileAwareAuthorizationRequestResolver`가 `code_challenge`도 보존. state 채널에 함께 싣거나(예: `.mobile.<cc>` 인코딩) authorization-request additionalParameters로 저장 후 success handler에서 복원. (권장: 별도 Redis 키 `pkce:<state>` → challenge, TTL=인증창 수명.)
2. **AuthCodeStore**(신규, Redis): `issue(userId, codeChallenge) → code`(랜덤 32B base64url, `authcode:<hash>` TTL 60s), `consume(code) → {userId, codeChallenge}`(검증 즉시 삭제, 1회성). `RefreshTokenStore`와 동일 패턴.
3. **OAuth2LoginSuccessHandler**(모바일 분기 교체): 토큰 대신 `devpath://callback?code=<authCode>` 리다이렉트.
4. **신규 엔드포인트** `POST /auth/oauth/token` (permitAll):
   - 요청: `{ "code": "...", "code_verifier": "..." }`
   - 처리: `consume(code)` → `BASE64URL(SHA256(code_verifier)) == codeChallenge` 검증(불일치/만료 401) → access mint + refresh issue.
   - 응답(웹과 분리, 토큰-바디): `{ "access_token": "...", "refresh_token": "...", "user": {…} }`.
5. (선택) 전환기: 기존 fragment-토큰 분기를 플래그로 병행 후 제거.

### 모바일 변경 (devpath-frontend)
1. **PkcePair**(순수 Dart): `verifier`(43–128자 base64url-noPad), `challenge=base64url(sha256(verifier))`. `crypto` 패키지. 단위테스트 용이.
2. `login()`: verifier 생성·임시 보관(메모리/secure_storage) + authorize URL에 `code_challenge`,`code_challenge_method=S256` 부가.
3. `auth_callback.dart`: `code`만 파싱(토큰 파싱 제거 또는 병행).
4. `auth_controller.completeFromDeepLink` 대체: code 수신 → `POST /auth/oauth/token {code, verifier}` → 토큰 저장 → bootstrap.
5. 목 모드: `mockLogin`은 교환 단계를 가짜 코드/토큰으로 대체.

### 보안 효과
토큰이 URL에서 사라짐(노출면 제거) + code는 1회용·단명·PKCE 바인딩 → custom scheme 가로채기를 당해도 verifier 없이는 교환 불가.

### 테스트(TDD)
- 백엔드 단위: AuthCodeStore issue/consume(1회성·만료), PKCE S256 검증, /auth/oauth/token(정상/잘못된 verifier/만료 code → 401). 통합: 성공핸들러 모바일=`?code=` 리다이렉트.
- 모바일 단위: PkcePair(알려진 벡터로 challenge 검증), 콜백 code 파싱, 교환 호출.

---

## 트랙 B — App Links / Universal Links (custom scheme 대체)

**목표**: OS가 도메인 소유권을 검증하는 https 링크로 바꿔 앱 가로채기를 차단.

### Android App Links
- `AndroidManifest`: 기존 `devpath://callback` intent-filter를 https로 추가
  ```xml
  <intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <category android:name="android.intent.category.BROWSABLE"/>
    <data android:scheme="https" android:host="<도메인>" android:path="/auth/mobile-callback"/>
  </intent-filter>
  ```
- 호스팅: `https://<도메인>/.well-known/assetlinks.json` — package_name + **서명 인증서 SHA-256 지문**(디버그/릴리스/Play 앱서명 키별 모두 등록).

### iOS Universal Links
- Xcode: **Associated Domains** capability + `applinks:<도메인>`(`Runner.entitlements`에 추가).
- 호스팅: `https://<도메인>/.well-known/apple-app-site-association`(JSON, `application/json`, 리다이렉트 금지) — `appID = <TeamID>.<bundleID>`, paths `["/auth/mobile-callback"]`.

### 백엔드/인프라
- success handler 리다이렉트 타깃을 `https://<도메인>/auth/mobile-callback?code=…`로 변경(트랙 A와 결합 시 code 전달).
- **결정 필요**: 어떤 도메인/호스트가 두 well-known 파일을 서빙하는가(devpath-landing-page? gateway?). HTTPS·정확한 content-type·무리다이렉트 필수.

### 모바일
- `app_links`는 https 딥링크도 처리 → 네이티브 설정만 추가. 전환기엔 custom scheme를 폴백으로 유지.

### 트레이드오프/주의
- 검증 도메인 + 자산 호스팅 + 빌드variant별 서명 지문 필요(인프라 의존 큼). 콜드스타트/deferred deep link 주의. 트랙 A와 함께 가면 "https 링크 + 1회용 code"로 노출·가로채기 모두 차단.

---

## 트랙 C — 디바이스 토큰 서버 등록 (FCM 타깃 발송 전제)

**목표**: 로그인 사용자에 FCM 디바이스 토큰을 연결해 타깃 푸시 가능하게.

### 백엔드 (2026-07-01부터 devpath-notification-svc, 게이트웨이 `/notifications/**` 경유 — platform-svc에서 이관됨)
- `POST /notifications/devices`(Bearer): `{ "token": "...", "platform": "ANDROID|IOS" }` → 사용자별 upsert(중복 제거).
- `DELETE /notifications/devices`(또는 logout 시): 해당 토큰 폐기.
- 저장: `device_tokens(user_id, token, platform, updated_at)`(devpath-shared 중앙 마이그레이션, 서비스 이관과 무관하게 테이블은 그대로).

### 모바일
- 로그인/부팅 인증 후: `FcmPushService.getToken()`(이미 존재, `push_service.dart`) → 등록 호출.
- 토큰 갱신(`onTokenRefresh`) 시 재등록, logout 시 해제.
- 목 모드: 스텁 토큰 등록(no-op 픽스처).

### 의존성
실 FCM 활성화(PR #48 외부 설정) 후 실토큰 확보 가능. 그 전엔 계약·목까지만.

### 테스트(TDD)
- 백엔드 단위/슬라이스: upsert 멱등성, 인증 필요, 폐기. 모바일 단위: 로그인 후 등록 호출, 갱신 재등록.

---

## 권장 순서 & 의존성
1. **A**(코드+PKCE) — 가장 큰 보안 이득, 인프라 의존 없음. B의 토대(리다이렉트에 code).
2. **C**(디바이스 등록) — 소규모, FCM 실사용 전제. A와 독립.
3. **B**(App/Universal Links) — 도메인 자산 호스팅 필요(인프라). A 위에 얹으면 최상.

## 팀 결정 필요 사항
- B의 well-known 자산을 서빙할 **도메인/호스트**.
- 전환기 동안 기존 "fragment 토큰" 분기 **병행 유지 기간**(앱 배포 롤아웃 대비).
- PKCE를 **백엔드 발급 1회용 code**로 갈지(권장 — 백엔드가 GitHub 중개 중) GitHub 직접 public-client로 갈지.
- C의 `device_tokens` 마이그레이션은 중앙(devpath-shared) 정책에 맞춰 추가.
