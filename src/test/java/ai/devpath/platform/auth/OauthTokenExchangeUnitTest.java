package ai.devpath.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.platform.auth.dto.LoginResponse;
import ai.devpath.platform.auth.dto.OauthTokenRequest;
import ai.devpath.platform.auth.jwt.JwtService;
import ai.devpath.platform.auth.refresh.RefreshTokenStore;
import ai.devpath.platform.config.AuthProperties;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import ai.devpath.platform.mentor.MentorAccess;
import ai.devpath.platform.mentor.MentorAccessService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 순수 단위테스트(Redis 불요). 모바일 PKCE 토큰 교환({@code POST /auth/oauth/token})의
 * 검증 로직. AuthCode Redis 연동 통합은 {@link AuthCodeStoreTest}.
 */
class OauthTokenExchangeUnitTest {

	// RFC 7636 Appendix B 벡터
	static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
	static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

	private RefreshTokenStore store;
	private JwtService jwt;
	private UserRepository users;
	private AuthCodeStore codes;
	private AuthController controller;
	private MentorAccessService mentorAccess;
	private User user;

	@BeforeEach
	void setUp() {
		store = mock(RefreshTokenStore.class);
		jwt = mock(JwtService.class);
		users = mock(UserRepository.class);
		codes = mock(AuthCodeStore.class);
		mentorAccess = mock(MentorAccessService.class);
		controller = new AuthController(store, jwt, new RefreshCookies(new AuthProperties()), users, codes,
			mentorAccess);

		user = mock(User.class);
		when(user.getId()).thenReturn(7L);
		when(user.getRole()).thenReturn("LEARNER");
		when(user.getEmail()).thenReturn("learner@devpath.ai");
		when(user.getNickname()).thenReturn("지수");
		when(user.getOnboardingStatus()).thenReturn("DONE");
		when(users.findById(7L)).thenReturn(Optional.of(user));
		when(mentorAccess.ensureForLogin(user)).thenReturn(MentorAccess.active(7L, "INVITE_CODE"));
		when(jwt.mintAccessToken(7L, "LEARNER", "ACTIVE")).thenReturn("acc");
		when(store.issue(7L)).thenReturn("refresh-new");
	}

	@Test
	void validExchangeMintsAccessAndIssuesRefreshInBody() {
		when(codes.consume("the-code")).thenReturn(Optional.of(new AuthCodeStore.Consumed(7L, CHALLENGE)));

		ResponseEntity<?> r = controller.exchange(new OauthTokenRequest("the-code", VERIFIER));

		assertEquals(200, r.getStatusCode().value());
		LoginResponse body = (LoginResponse) r.getBody();
		assertNotNull(body);
		assertEquals("acc", body.accessToken());
		assertEquals("refresh-new", body.refreshToken());
		assertEquals("7", body.user().id());
	}

	@Test
	void wrongVerifierIs401() {
		when(codes.consume("the-code")).thenReturn(Optional.of(new AuthCodeStore.Consumed(7L, CHALLENGE)));
		ResponseEntity<?> r = controller.exchange(new OauthTokenRequest("the-code", "wrong-verifier"));
		assertEquals(401, r.getStatusCode().value());
	}

	@Test
	void unknownOrExpiredCodeIs401() {
		when(codes.consume("bad")).thenReturn(Optional.empty());
		ResponseEntity<?> r = controller.exchange(new OauthTokenRequest("bad", VERIFIER));
		assertEquals(401, r.getStatusCode().value());
	}

	@Test
	void missingFieldsIs401() {
		assertEquals(401, controller.exchange(null).getStatusCode().value());
		assertEquals(401, controller.exchange(new OauthTokenRequest(null, VERIFIER)).getStatusCode().value());
		assertEquals(401, controller.exchange(new OauthTokenRequest("the-code", "  ")).getStatusCode().value());
	}

	/**
	 * 안티-리플레이: verifier가 틀려도 code는 이미 소비(burn)된다. consume이 검증보다 먼저
	 * 호출되므로(소비-후-검증), 탈취된 code로 verifier를 바꿔가며 재시도해도 두 번째부터는
	 * code가 없어 실패한다. 본 테스트는 실패 교환에서도 consume이 1회 호출됨을 고정한다.
	 */
	@Test
	void failedExchangeStillConsumesCodeOnce() {
		when(codes.consume("the-code")).thenReturn(Optional.of(new AuthCodeStore.Consumed(7L, CHALLENGE)));
		controller.exchange(new OauthTokenRequest("the-code", "wrong-verifier"));
		verify(codes).consume("the-code");
	}

	/**
	 * 다운그레이드 차단: challenge가 빈 code(=PKCE 없이 발급된 것처럼 보이는 값)는 어떤
	 * verifier로도 교환되지 않는다(빈 challenge는 {@link Pkce#matches}가 항상 false).
	 */
	@Test
	void emptyChallengeCannotBeExchanged() {
		when(codes.consume("the-code")).thenReturn(Optional.of(new AuthCodeStore.Consumed(7L, "")));
		ResponseEntity<?> r = controller.exchange(new OauthTokenRequest("the-code", VERIFIER));
		assertEquals(401, r.getStatusCode().value());
	}

	/** PKCE는 통과했지만 사용자 행이 사라진 경우(발급 후 탈퇴 등) → 401. */
	@Test
	void userMissingAfterValidPkceIs401() {
		when(codes.consume("the-code")).thenReturn(Optional.of(new AuthCodeStore.Consumed(999L, CHALLENGE)));
		when(users.findById(999L)).thenReturn(Optional.empty());
		ResponseEntity<?> r = controller.exchange(new OauthTokenRequest("the-code", VERIFIER));
		assertEquals(401, r.getStatusCode().value());
	}

	@Test
	void softDeletedUserCannotExchangeValidPkceForNewCredentials() {
		when(user.getDeletedAt()).thenReturn(java.time.Instant.parse("2026-09-05T03:00:00Z"));
		when(codes.consume("the-code")).thenReturn(Optional.of(new AuthCodeStore.Consumed(7L, CHALLENGE)));

		ResponseEntity<?> r = controller.exchange(new OauthTokenRequest("the-code", VERIFIER));

		assertEquals(401, r.getStatusCode().value());
		verifyNoInteractions(mentorAccess, jwt, store);
	}
}
