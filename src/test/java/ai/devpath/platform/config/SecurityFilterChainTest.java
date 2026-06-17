package ai.devpath.platform.config;

import ai.devpath.platform.auth.jwt.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityFilterChainTest {

	@Autowired MockMvc mvc;
	@Autowired JwtService jwt;

	@Test
	void protectedEndpointWithoutTokenIs401() throws Exception {
		mvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void protectedEndpointWithValidJwtIsNot401() throws Exception {
		String token = jwt.mintAccessToken(1L, "LEARNER");
		mvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
				.andExpect(status().is(org.hamcrest.Matchers.not(401)));
	}

	@Test
	void oauthAuthorizationEndpointRedirects() throws Exception {
		mvc.perform(get("/oauth2/authorization/github")).andExpect(status().is3xxRedirection());
	}
}
