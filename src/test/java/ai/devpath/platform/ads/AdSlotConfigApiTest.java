package ai.devpath.platform.ads;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.platform.auth.jwt.JwtService;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
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
class AdSlotConfigApiTest {

  @Autowired MockMvc mvc;
  @Autowired JwtService jwt;
  @Autowired UserRepository users;
  @Autowired AdSlotConfigService service;

  private String adminToken() {
    User u = new User();
    u.setEmail("adslot-" + System.nanoTime() + "@example.com");
    u.setNickname("관리자");
    u.setRole("ADMIN");
    u.setStatus("ACTIVE");
    u.setOnboardingStatus("PENDING");
    u = users.save(u);
    return jwt.mintAccessToken(u.getId(), "ADMIN");
  }

  /** 공유 테스트 DB이므로 세 행 전부를 시드 상태로 되돌린다(실행 순서 독립). */
  @AfterEach
  void restoreSeed() {
    for (String slot : new String[] {"DASHBOARD_TOP", "COMMUNITY_FEED", "CONTENT_PAGE"}) {
      service.update(slot, "HOUSE", null);
    }
  }

  @Test
  void listReturnsThreeSlots() throws Exception {
    mvc.perform(get("/admin/ads/slot-config")
            .header("Authorization", "Bearer " + adminToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].slot").value("COMMUNITY_FEED"));
  }

  @Test
  void updateStoresSourceAndUnitId() throws Exception {
    mvc.perform(put("/admin/ads/slot-config/DASHBOARD_TOP")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"source\":\"ADSENSE\",\"adsenseSlotId\":\"1234567890\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("ADSENSE"))
        .andExpect(jsonPath("$.adsenseSlotId").value("1234567890"));
  }

  @Test
  void blankUnitIdIsStoredAsNullAndAllowed() throws Exception {
    mvc.perform(put("/admin/ads/slot-config/DASHBOARD_TOP")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"source\":\"ADSENSE\",\"adsenseSlotId\":\"   \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("ADSENSE"))
        .andExpect(jsonPath("$.adsenseSlotId").doesNotExist());
  }

  @Test
  void unknownSourceIsRejectedWith400() throws Exception {
    mvc.perform(put("/admin/ads/slot-config/DASHBOARD_TOP")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"source\":\"BANNERFLOW\",\"adsenseSlotId\":null}"))
        .andExpect(status().isBadRequest());
  }
}
