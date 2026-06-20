## Step 1: #1 OAuth/인증 (게이트)

> **상태(2026-06-19)**: 부분 구현. `AuthController`의 refresh/logout, `UserController`의 `/users/me`, `UserRegisteredEvent` 발행, 진단 완료 이벤트 소비가 코드에 존재한다. 외부 OAuth 앱 심사와 전체 로그인 E2E는 아직 목표 상태다.

### 1.1 Spring Security + OAuth2 Client
- [ ] Spring Security 7 + OAuth2 Client (GitHub → Google → 카카오 순) — 부분 구현
- [ ] JWT + Refresh Cookie — 부분 구현
### 1.2 이벤트
- [x] UserRegisteredEvent Outbox
- [x] AssessmentCompletedEvent 소비로 onboarding_status 전이
### 1.3 외부 의존성(횡단)
- [ ] 카카오/Google OAuth 앱 심사 신청
- [ ] Anthropic 프로덕션 한도 신청
