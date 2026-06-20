package ai.devpath.platform.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.devpath.platform.auth.jwt.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerTest {

	@Autowired MockMvc mvc;
	@Autowired JwtService jwt;
	@Autowired UserRepository users;

	@Test
	void meReturnsProfileForAuthenticatedUser() throws Exception {
		User u = new User();
		u.setEmail("m" + System.nanoTime() + "@example.com");
		u.setNickname("지수"); u.setRole("LEARNER"); u.setStatus("ACTIVE"); u.setOnboardingStatus("PENDING");
		u = users.save(u);
		String token = jwt.mintAccessToken(u.getId(), "LEARNER");

		mvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(String.valueOf(u.getId())))
				.andExpect(jsonPath("$.email").value(u.getEmail()))
				.andExpect(jsonPath("$.nickname").value("지수"))
				.andExpect(jsonPath("$.role").value("LEARNER"))
				.andExpect(jsonPath("$.onboardingStatus").value("PENDING"));
	}
}
