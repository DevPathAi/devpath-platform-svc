package ai.devpath.platform.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.devpath.platform.auth.refresh.RefreshTokenStore;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

	@Autowired MockMvc mvc;
	@Autowired RefreshTokenStore refreshStore;
	@Autowired UserRepository users;

	@Test
	void refreshWithValidCookieReturnsAccessTokenAndRotates() throws Exception {
		User u = new User();
		u.setEmail("r" + System.nanoTime() + "@example.com");
		u.setNickname("지수"); u.setRole("LEARNER"); u.setStatus("ACTIVE"); u.setOnboardingStatus("PENDING");
		u = users.save(u);
		String refresh = refreshStore.issue(u.getId());

		mvc.perform(post("/auth/refresh").cookie(new Cookie("refresh_token", refresh)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.access_token").isNotEmpty())
				.andExpect(jsonPath("$.refresh_token_cookie_set").value(true))
				.andExpect(jsonPath("$.user.id").value(String.valueOf(u.getId())))
				.andExpect(jsonPath("$.user.email").value(u.getEmail()))
				.andExpect(header().exists("Set-Cookie"));
	}

	@Test
	void refreshWithoutCookieIs401() throws Exception {
		mvc.perform(post("/auth/refresh")).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutClearsCookieAnd204() throws Exception {
		String refresh = refreshStore.issue(1L);
		mvc.perform(post("/auth/logout").cookie(new Cookie("refresh_token", refresh)))
				.andExpect(status().isNoContent())
				.andExpect(header().exists("Set-Cookie"));
	}
}
