package ai.devpath.platform.beta;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BetaStatusControllerTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired BetaStatusTokens tokens;

    private User saveUser(String status) {
        User u = new User();
        u.setEmail("beta-" + System.nanoTime() + "@example.com");
        u.setNickname("t");
        u.setStatus(status);
        return userRepository.save(u);
    }

    @Test
    void pendingUser_returnsPending() throws Exception {
        User u = saveUser("BETA_PENDING");
        String t = tokens.issue(u.getId());
        mvc.perform(get("/beta/status").cookie(new Cookie("beta_status", t)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void activeUser_returnsApproved() throws Exception {
        User u = saveUser("ACTIVE");
        String t = tokens.issue(u.getId());
        mvc.perform(get("/beta/status").cookie(new Cookie("beta_status", t)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void noCookie_returnsExpired() throws Exception {
        mvc.perform(get("/beta/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }
}
