# devpath-platform-svc

**DevPath AI** 플랫폼 서비스 — 사용자/인증, GitHub 수집을 담당합니다.

## 담당 도메인

| 모듈 | 역할 |
|------|------|
| user | 사용자 계정, OAuth2(**GitHub·Google**) 연동, 이메일 기준 계정 통합, JWT |
| github | GitHub 프로필/활동 수집 워커 |
| consent | 개인정보/약관 동의 이력, 14세 차단, 계정 삭제·로그아웃 |
| mypage | 프로필 조회·수정(bio·목표·트랙·경력), 아바타 업로드/삭제(오브젝트 스토리지) |
| beta | 베타 게이팅 — 승인 상태 폴링(`/beta/status`), admin 승인/허용리스트 |

> 알림(FCM 디바이스 토큰, 인앱 알림) 모듈은 2026-07-01 [devpath-notification-svc](https://github.com/DevPathAi/devpath-notification-svc)로 이관되었습니다.

## 구성

- Spring Boot 4.0.x · Java 21 · Gradle (Kotlin DSL)
- [devpath-svc-template](https://github.com/DevPathAi/devpath-svc-template) 기반
- DB 의존성(JPA + PostgreSQL, Redis)은 `build.gradle.kts` 주석 해제로 활성화

## 빌드 / 실행

```bash
./gradlew build
./gradlew bootRun    # 기본 포트 8080
```

로컬 인프라는 [devpath-shared](https://github.com/DevPathAi/devpath-shared)의 docker-compose를 사용합니다.

## 개발 규칙

- Git 규칙: [documents/09_Git_규칙_정의서](https://github.com/DevPathAi/documents/blob/main/09_Git_규칙_정의서.md)
- 워크플로우 현황: `docs/project-management/` → [workflow-dashboard](https://devpathai.github.io/workflow-dashboard/)
