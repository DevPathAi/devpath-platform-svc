package ai.devpath.platform.ads;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.platform.auth.jwt.JwtService;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** GET /ads 판별 유니온 봉투 계약. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdServeContractTest {

  @Autowired MockMvc mvc;
  @Autowired JwtService jwt;
  @Autowired UserRepository users;
  @MockitoBean AdServeService serve;

  private String token() {
    User u = new User();
    u.setEmail("adserve-" + System.nanoTime() + "@example.com");
    u.setNickname("광고유저");
    u.setRole("LEARNER");
    u.setStatus("ACTIVE");
    u.setOnboardingStatus("PENDING");
    u = users.save(u);
    return jwt.mintAccessToken(u.getId(), "LEARNER");
  }

  @Test
  void houseAdIsWrappedInEnvelope() throws Exception {
    when(serve.serve(anyString(), anyLong())).thenReturn(Optional.of(
        new AdSlotContent.House(
            new AdView(7L, "배너", null, "https://e.com/land", "DASHBOARD_TOP"))));

    mvc.perform(get("/ads").param("slot", "DASHBOARD_TOP")
            .header("Authorization", "Bearer " + token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("HOUSE"))
        .andExpect(jsonPath("$.ad.id").value(7))
        .andExpect(jsonPath("$.ad.linkUrl").value("https://e.com/land"))
        .andExpect(jsonPath("$.adsenseSlotId").doesNotExist());
  }

  @Test
  void adsenseUnitIsWrappedInEnvelope() throws Exception {
    when(serve.serve(anyString(), anyLong()))
        .thenReturn(Optional.of(new AdSlotContent.Adsense("1234567890")));

    mvc.perform(get("/ads").param("slot", "DASHBOARD_TOP")
            .header("Authorization", "Bearer " + token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("ADSENSE"))
        .andExpect(jsonPath("$.adsenseSlotId").value("1234567890"))
        .andExpect(jsonPath("$.ad").doesNotExist());
  }

  @Test
  void emptyServeIsNoContent() throws Exception {
    when(serve.serve(anyString(), anyLong())).thenReturn(Optional.empty());

    mvc.perform(get("/ads").param("slot", "DASHBOARD_TOP")
            .header("Authorization", "Bearer " + token()))
        .andExpect(status().isNoContent());
  }
}
