package ai.devpath.platform.beta;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.devpath.platform.auth.jwt.JwtService;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AdminUserController 통합 테스트.
 * 전체 Spring 컨텍스트(@SpringBootTest)를 사용해 실제 SecurityFilterChain과
 * JwtDecoder가 연결된 상태에서 /admin/** 엔드포인트를 검증한다.
 * - ADMIN JWT → 200/204
 * - LEARNER JWT → 403
 * - 미인증 → 401
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUserControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired UserRepository userRepository;

    // ---------------------------------------------------------------
    // 헬퍼
    // ---------------------------------------------------------------

    private User createBetaPendingUser(String emailPrefix) {
        User u = new User();
        u.setEmail(emailPrefix + System.nanoTime() + "@example.com");
        u.setNickname("테스트유저");
        u.setRole("LEARNER");
        u.setStatus("BETA_PENDING");
        u.setOnboardingStatus("PENDING");
        return userRepository.save(u);
    }

    private String adminToken() {
        // id=0은 실제 user가 없어도 되는 dummy; ADMIN role만 중요
        return jwt.mintAccessToken(0L, "ADMIN");
    }

    private String learnerToken(long userId) {
        return jwt.mintAccessToken(userId, "LEARNER");
    }

    // ---------------------------------------------------------------
    // GET /admin/users — 목록 조회
    // ---------------------------------------------------------------

    @Test
    void listUsers_adminJwt_returns200WithPageShape() throws Exception {
        User u = createBetaPendingUser("admin-list-");

        mvc.perform(get("/admin/users")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.limit").isNumber());
    }

    @Test
    void listUsers_statusFilter_returnsBetaPendingOnly() throws Exception {
        User u = createBetaPendingUser("admin-pending-");

        mvc.perform(get("/admin/users")
                        .param("status", "BETA_PENDING")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                // 모든 반환 row의 status가 BETA_PENDING임을 확인
                .andExpect(jsonPath("$.data[*].status").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("BETA_PENDING"))))
                .andExpect(jsonPath("$.limit").isNumber());
    }

    @Test
    void listUsers_nextCursorNullWhenNoNextPage() throws Exception {
        // cursor를 Long.MAX_VALUE에 가까운 값으로 설정하면 결과가 0건 → nextCursor == null
        mvc.perform(get("/admin/users")
                        .param("cursor", String.valueOf(Long.MAX_VALUE - 1))
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void listUsers_learnerJwt_returns403() throws Exception {
        User u = createBetaPendingUser("learner-list-");
        mvc.perform(get("/admin/users")
                        .header("Authorization", "Bearer " + learnerToken(u.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUsers_noAuth_returns401() throws Exception {
        mvc.perform(get("/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------
    // POST /admin/users/{id}/approve — 승인
    // ---------------------------------------------------------------

    @Test
    void approveUser_adminJwt_returns204() throws Exception {
        User u = createBetaPendingUser("approve-");

        mvc.perform(post("/admin/users/" + u.getId() + "/approve")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNoContent());

        // DB에서 ACTIVE로 변경됐는지 확인
        User updated = userRepository.findById(u.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("ACTIVE", updated.getStatus());
    }

    @Test
    void approveUser_learnerJwt_returns403() throws Exception {
        User u = createBetaPendingUser("approve-learner-");
        mvc.perform(post("/admin/users/" + u.getId() + "/approve")
                        .header("Authorization", "Bearer " + learnerToken(u.getId())))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------
    // POST /admin/allowlist — 사전승인
    // ---------------------------------------------------------------

    @Test
    void preApprove_adminJwt_returns204() throws Exception {
        String email = "preapprove-" + System.nanoTime() + "@example.com";

        mvc.perform(post("/admin/allowlist")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void preApprove_learnerJwt_returns403() throws Exception {
        User u = createBetaPendingUser("preapprove-learner-");
        mvc.perform(post("/admin/allowlist")
                        .header("Authorization", "Bearer " + learnerToken(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@y.com\"}"))
                .andExpect(status().isForbidden());
    }
}
