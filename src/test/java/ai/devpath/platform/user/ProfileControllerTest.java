package ai.devpath.platform.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.platform.auth.jwt.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileControllerTest {

  @Autowired MockMvc mvc;
  @Autowired JwtService jwt;
  @Autowired UserRepository users;

  private User newUser() {
    User u = new User();
    u.setEmail("profile-" + System.nanoTime() + "@example.com");
    u.setNickname("프로필유저");
    u.setRole("LEARNER");
    u.setStatus("ACTIVE");
    u.setOnboardingStatus("PENDING");
    return users.save(u);
  }

  @Test
  void getReturnsEmptyProfileWhenNoneSaved() throws Exception {
    User u = newUser();
    String token = jwt.mintAccessToken(u.getId(), "LEARNER");
    mvc.perform(get("/users/me/profile").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bio").doesNotExist());
  }

  @Test
  void putSavesAndReturnsProfile() throws Exception {
    User u = newUser();
    String token = jwt.mintAccessToken(u.getId(), "LEARNER");
    mvc.perform(
            put("/users/me/profile")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"bio\":\"백엔드 지망\",\"learningGoal\":\"JOB\",\"targetTrack\":\"BACKEND_SPRING\",\"experienceYears\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bio").value("백엔드 지망"))
        .andExpect(jsonPath("$.experienceYears").value(2));

    mvc.perform(get("/users/me/profile").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetTrack").value("BACKEND_SPRING"));
  }

  @Test
  void putRejectsInvalidExperienceYearsWith400() throws Exception {
    User u = newUser();
    String token = jwt.mintAccessToken(u.getId(), "LEARNER");
    mvc.perform(
            put("/users/me/profile")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"experienceYears\":99}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
  }
}
