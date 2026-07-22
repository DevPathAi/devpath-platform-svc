package ai.devpath.platform.ads;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.platform.auth.jwt.JwtService;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdControllerTest {

  @Autowired MockMvc mvc;
  @Autowired JwtService jwt;
  @Autowired UserRepository users;
  @MockitoBean AdEventService eventService;

  private String token() {
    User u = new User();
    u.setEmail("ad-" + System.nanoTime() + "@example.com");
    u.setNickname("광고유저");
    u.setRole("LEARNER");
    u.setStatus("ACTIVE");
    u.setOnboardingStatus("PENDING");
    u = users.save(u);
    return jwt.mintAccessToken(u.getId(), "LEARNER");
  }

  /**
   * I4: existsById 통과 후 upsert 직전 광고가 동시 삭제되면 FK 위반으로
   * DataIntegrityViolationException이 발생한다. 이벤트는 유실 허용(spec)이므로
   * 500이 아니라 202로 흡수해야 한다.
   */
  @Test
  void concurrentDeleteRaceIsAbsorbedAs202() throws Exception {
    doThrow(new DataIntegrityViolationException("fk violation"))
        .when(eventService)
        .record(anyLong(), anyString());

    mvc.perform(post("/ads/1/events")
            .header("Authorization", "Bearer " + token())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"IMPRESSION\"}"))
        .andExpect(status().isAccepted());
  }

  /** 존재하지 않는 광고 id(클라이언트 오류)는 흡수하지 않고 404로 유지한다. */
  @Test
  void unknownAdStillReturns404() throws Exception {
    doThrow(new AdNotFoundException(999_999L))
        .when(eventService)
        .record(anyLong(), anyString());

    mvc.perform(post("/ads/999999/events")
            .header("Authorization", "Bearer " + token())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"IMPRESSION\"}"))
        .andExpect(status().isNotFound());
  }
}
