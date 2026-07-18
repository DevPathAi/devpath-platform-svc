package ai.devpath.platform.beta;

import ai.devpath.platform.beta.dto.BetaStatusResponse;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserOauthIdentity;
import ai.devpath.platform.user.UserOauthIdentityRepository;
import ai.devpath.platform.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 미승인자 대기 페이지의 승인여부 폴링 엔드포인트(공개 — beta_status 쿠키로 자체 검증).
 * 실 인증(JWT)과 무관하며 조회 결과는 PENDING/APPROVED/EXPIRED 뿐이다.
 */
@RestController
public class BetaStatusController {

    private final BetaStatusTokens tokens;
    private final UserRepository users;
    private final UserOauthIdentityRepository identities;

    public BetaStatusController(BetaStatusTokens tokens, UserRepository users,
            UserOauthIdentityRepository identities) {
        this.tokens = tokens;
        this.users = users;
        this.identities = identities;
    }

    @GetMapping("/beta/status")
    public BetaStatusResponse status(HttpServletRequest request) {
        Optional<Long> userId = tokens.validate(readCookie(request, "beta_status"));
        if (userId.isEmpty()) return new BetaStatusResponse("EXPIRED", null);
        Optional<User> user = users.findById(userId.get());
        if (user.isEmpty()) return new BetaStatusResponse("EXPIRED", null);
        if ("ACTIVE".equals(user.get().getStatus())) {
            return new BetaStatusResponse("APPROVED", firstProvider(userId.get()));
        }
        return new BetaStatusResponse("PENDING", null);
    }

    private String firstProvider(long userId) {
        List<UserOauthIdentity> list = identities.findByUserIdOrderByLinkedAtAsc(userId);
        return list.isEmpty() ? null : list.get(0).getProvider().toLowerCase();
    }

    private static String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (var c : request.getCookies()) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
