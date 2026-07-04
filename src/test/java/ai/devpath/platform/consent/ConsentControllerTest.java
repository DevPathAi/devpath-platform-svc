package ai.devpath.platform.consent;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.devpath.platform.auth.jwt.JwtService;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsentControllerTest {

	@Autowired MockMvc mvc;
	@Autowired JwtService jwt;
	@Autowired UserRepository users;

	private User newUser(long id) {
		User u = new User();
		u.setEmail("consent" + id + "-" + System.nanoTime() + "@example.com");
		u.setNickname("동의유저"); u.setRole("LEARNER"); u.setStatus("ACTIVE"); u.setOnboardingStatus("PENDING");
		return users.save(u);
	}

	@Test
	void submittingBothRequiredConsentsMarksStatusDone() throws Exception {
		User u = newUser(990000901);
		String token = jwt.mintAccessToken(u.getId(), "LEARNER");

		mvc.perform(post("/consents")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"consents":[{"type":"TERMS","agreed":true},{"type":"PRIVACY","agreed":true}],"birthYear":2000}
					"""))
				.andExpect(status().isOk());

		mvc.perform(get("/consents/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.consentStatus").value("DONE"))
				.andExpect(jsonPath("$.birthYear").value(2000))
				.andExpect(jsonPath("$.items", hasSize(2)));
	}

	@Test
	void underAge14BirthYearIsRejectedWith400() throws Exception {
		User u = newUser(990000902);
		String token = jwt.mintAccessToken(u.getId(), "LEARNER");
		int tooYoungBirthYear = java.time.Year.now().getValue() - 10;

		mvc.perform(post("/consents")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"consents":[{"type":"TERMS","agreed":true},{"type":"PRIVACY","agreed":true}],"birthYear":%d}
					""".formatted(tooYoungBirthYear)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void revokingOptionalConsentSucceeds() throws Exception {
		User u = newUser(990000903);
		String token = jwt.mintAccessToken(u.getId(), "LEARNER");

		mvc.perform(post("/consents")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"consents":[{"type":"TERMS","agreed":true},{"type":"PRIVACY","agreed":true},{"type":"MARKETING","agreed":true}],"birthYear":2000}
					"""))
				.andExpect(status().isOk());

		mvc.perform(post("/consents/MARKETING/revoke").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());

		mvc.perform(get("/consents/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.consentStatus").value("DONE"));
	}

	@Test
	void revokingRequiredConsentIsConflict409() throws Exception {
		User u = newUser(990000904);
		String token = jwt.mintAccessToken(u.getId(), "LEARNER");

		mvc.perform(post("/consents")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"consents":[{"type":"TERMS","agreed":true},{"type":"PRIVACY","agreed":true}],"birthYear":2000}
					"""))
				.andExpect(status().isOk());

		mvc.perform(post("/consents/TERMS/revoke").header("Authorization", "Bearer " + token))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("CONFLICT"));
	}
}
