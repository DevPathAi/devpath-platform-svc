package ai.devpath.platform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.platform.support.dto.SupportCreateRequest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 관리자 API 의 권한·계약. 실서명 JWT 로 role→ROLE_* 변환기를 실제로 통과시킨다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminSupportControllerTest {

  @Value("${devpath.auth.jwt-secret}") String secret;

  @Autowired MockMvc mvc;
  @Autowired SupportService service;
  @Autowired SupportRequestRepository requests;

  @Test
  void listIsForbiddenForNonAdmin() throws Exception {
    mvc.perform(get("/admin/support-requests")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("7", "LEARNER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void listIsUnauthorizedWithoutToken() throws Exception {
    mvc.perform(get("/admin/support-requests")).andExpect(status().isUnauthorized());
  }

  @Test
  void listReturnsPageEnvelopeNewestFirst() throws Exception {
    long a = seed("먼저 접수").getId();
    long b = seed("나중 접수").getId();

    mvc.perform(get("/admin/support-requests?status=OPEN&limit=100")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("1", "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").value(100))
        .andExpect(jsonPath("$.data").isArray());

    // 최신순: 나중에 만든 b 가 a 보다 앞에 온다.
    String body = mvc.perform(get("/admin/support-requests?limit=100")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("1", "ADMIN")))
        .andReturn().getResponse().getContentAsString();
    assertThat(body.indexOf("\"id\":" + b)).isLessThan(body.indexOf("\"id\":" + a));
  }

  @Test
  void detailIncludesFailuresInSeqOrder() throws Exception {
    long id = seedWithFailures().getId();

    mvc.perform(get("/admin/support-requests/" + id)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("1", "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.failures.length()").value(2))
        .andExpect(jsonPath("$.failures[0].seq").value(0))
        .andExpect(jsonPath("$.failures[1].seq").value(1))
        .andExpect(jsonPath("$.failures[0].path").value("/first"));
  }

  @Test
  void detailIsNotFoundForUnknownId() throws Exception {
    mvc.perform(get("/admin/support-requests/99999999")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("1", "ADMIN")))
        .andExpect(status().isNotFound());
  }

  @Test
  void statusTransitionRecordsHandler() throws Exception {
    long id = seed("전이 대상").getId();

    mvc.perform(post("/admin/support-requests/" + id + "/status")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("3", "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"IN_PROGRESS\",\"adminNote\":\"재현 확인함\"}"
                .getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.adminNote").value("재현 확인함"))
        .andExpect(jsonPath("$.handledBy").value(3));

    SupportRequest after = requests.findById(id).orElseThrow();
    assertThat(after.getHandledAt()).isNotNull();
  }

  @Test
  void reopeningClearsHandler() throws Exception {
    long id = seed("되돌릴 건").getId();
    service.updateStatus(id, 3L, "RESOLVED", "처리함");

    mvc.perform(post("/admin/support-requests/" + id + "/status")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("3", "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"OPEN\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OPEN"));

    SupportRequest after = requests.findById(id).orElseThrow();
    assertThat(after.getHandledBy()).isNull();
    assertThat(after.getHandledAt()).isNull();
  }

  @Test
  void rejectsUnknownStatus() throws Exception {
    long id = seed("이상값").getId();

    mvc.perform(post("/admin/support-requests/" + id + "/status")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("3", "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"BOGUS\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void statusTransitionIsForbiddenForNonAdmin() throws Exception {
    long id = seed("권한 확인").getId();

    mvc.perform(post("/admin/support-requests/" + id + "/status")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("7", "LEARNER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"RESOLVED\"}"))
        .andExpect(status().isForbidden());
  }

  private SupportRequest seed(String title) {
    return service.create(7L,
        new SupportCreateRequest("ERROR", title, "본문", null));
  }

  private SupportRequest seedWithFailures() {
    var ctx = new SupportCreateRequest.Context("/path", "0.1.0", "UA", "800x600", null, null,
        "2026-08-03T10:00:00Z",
        List.of(
            new SupportCreateRequest.Failure("GET", "/first", 500, "INTERNAL_ERROR", null,
                "첫 실패", "2026-08-03T10:00:01Z"),
            new SupportCreateRequest.Failure("POST", "/second", null, null, null,
                "두 번째", "2026-08-03T10:00:02Z")));
    return service.create(7L, new SupportCreateRequest("ERROR", "실패 포함", "본문", ctx));
  }

  private String token(String sub, String role) throws Exception {
    JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
        .subject(sub)
        .issueTime(new Date())
        .expirationTime(new Date(System.currentTimeMillis() + 60_000));
    if (role != null) {
      claims.claim("role", role);
    }
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
    jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }
}
