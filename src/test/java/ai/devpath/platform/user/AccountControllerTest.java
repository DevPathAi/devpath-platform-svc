package ai.devpath.platform.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.devpath.platform.auth.jwt.JwtService;
import ai.devpath.platform.auth.refresh.RefreshTokenStore;
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
class AccountControllerTest {

	@Autowired MockMvc mvc;
	@Autowired JwtService jwt;
	@Autowired UserRepository users;
	@Autowired RefreshTokenStore refreshStore;

	private User newUser(long id) {
		User u = new User();
		u.setEmail("account" + id + "-" + System.nanoTime() + "@example.com");
		u.setNickname("삭제유저"); u.setRole("LEARNER"); u.setStatus("ACTIVE"); u.setOnboardingStatus("PENDING");
		return users.save(u);
	}

	@Test
	void deletingAccountSoftDeletesAndReturns204() throws Exception {
		User u = newUser(990000905);
		String token = jwt.mintAccessToken(u.getId(), "LEARNER");

		mvc.perform(delete("/users/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent())
				.andExpect(header().exists("Set-Cookie"));

		User reloaded = users.findById(u.getId()).orElseThrow();
		org.junit.jupiter.api.Assertions.assertNotNull(reloaded.getDeletedAt());
	}

	@Test
	void deletingAccountRevokesRefreshTokenAndSubsequentRefreshIs401() throws Exception {
		User u = newUser(990000906);
		String token = jwt.mintAccessToken(u.getId(), "LEARNER");
		String refresh = refreshStore.issue(u.getId());

		mvc.perform(delete("/users/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent());

		mvc.perform(post("/auth/refresh").cookie(new Cookie("refresh_token", refresh)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void deleteWithoutAuthenticationIs401() throws Exception {
		mvc.perform(delete("/users/me")).andExpect(status().isUnauthorized());
	}
}
