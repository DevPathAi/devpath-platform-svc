package ai.devpath.platform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 접수 API 계약.
 *
 * <p>권한 테스트는 {@code jwt()} 후처리기가 아니라 <b>실제 HS256 서명 JWT</b>를 쓴다 —
 * 후처리기는 authority 를 직접 주입해 SecurityConfig 의 role→ROLE_* 변환기를 우회한다.
 *
 * <p>건수 단언은 <b>델타</b>로 한다(롤백 없는 통합 테스트라 절대값은 다른 테스트에 오염된다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupportControllerTest {

  @Value("${devpath.auth.jwt-secret}") String secret;

  @Autowired MockMvc mvc;
  @Autowired SupportRequestRepository requests;
  @Autowired SupportRequestFailureRepository failures;

  @Test
  void createsRequestWithMaskedFailures() throws Exception {
    long before = requests.count();

    String json = """
        {"type":"ERROR","title":"경로 화면이 멈춰요","body":"진행률 40%에서 멈춥니다.",
         "context":{"pagePath":"/path","appVersion":"0.1.0+42","userAgent":"UA","viewport":"1920x1080",
           "errorCode":"PATH_GENERATION_FAILED","occurredAt":"2026-08-03T10:11:12Z",
           "failures":[{"method":"POST","path":"/learning-paths","statusCode":500,
             "errorCode":"INTERNAL_ERROR","message":"문의: hong@example.com",
             "occurredAt":"2026-08-03T10:11:09Z"}]}}
        """;

    String res = mvc.perform(post("/support/requests")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("7", "LEARNER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNumber())
        .andReturn().getResponse().getContentAsString();

    assertThat(requests.count()).isEqualTo(before + 1);

    long id = Long.parseLong(res.replaceAll("\\D+", ""));
    SupportRequest saved = requests.findById(id).orElseThrow();
    assertThat(saved.getReporterId()).isEqualTo(7L);
    assertThat(saved.getStatus()).isEqualTo("OPEN");
    assertThat(saved.getPagePath()).isEqualTo("/path");

    var rows = failures.findByRequestIdOrderBySeqAsc(id);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getSeq()).isEqualTo((short) 0);
    // 서버 재마스킹 — 클라가 마스킹을 건너뛰었어도 원문이 저장되지 않는다.
    assertThat(rows.get(0).getMessage()).isEqualTo("문의: [EMAIL]");
  }

  @Test
  void reporterIdComesFromJwtNotBody() throws Exception {
    String json = """
        {"type":"INQUIRY","title":"문의","body":"내용","context":{"reporterId":999}}
        """;
    String res = mvc.perform(post("/support/requests")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("11", "LEARNER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();

    long id = Long.parseLong(res.replaceAll("\\D+", ""));
    assertThat(requests.findById(id).orElseThrow().getReporterId()).isEqualTo(11L);
  }

  @Test
  void keepsOnlyFirstTenFailures() throws Exception {
    StringBuilder items = new StringBuilder();
    for (int i = 0; i < 11; i++) {
      if (i > 0) {
        items.append(',');
      }
      items.append("""
          {"method":"GET","path":"/x/%d","statusCode":500,"occurredAt":"2026-08-03T10:00:00Z"}"""
          .formatted(i));
    }
    String json = """
        {"type":"ERROR","title":"제목","body":"본문","context":{"failures":[%s]}}"""
        .formatted(items);

    String res = mvc.perform(post("/support/requests")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("7", "LEARNER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();

    long id = Long.parseLong(res.replaceAll("\\D+", ""));
    // 초과분은 400 이 아니라 절단이다 — 부가 정보의 형식 문제로 제보를 거절하지 않는다.
    assertThat(failures.countByRequestId(id)).isEqualTo(10L);
  }

  @Test
  void rejectsBadType() throws Exception {
    mvc.perform(post("/support/requests")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("7", "LEARNER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"BOGUS\",\"title\":\"제목\",\"body\":\"본문\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsBlankTitleAndBody() throws Exception {
    mvc.perform(post("/support/requests")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("7", "LEARNER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"ERROR\",\"title\":\"   \",\"body\":\"본문\"}"))
        .andExpect(status().isBadRequest());

    mvc.perform(post("/support/requests")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("7", "LEARNER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"ERROR\",\"title\":\"제목\",\"body\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(post("/support/requests")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"ERROR\",\"title\":\"제목\",\"body\":\"본문\"}"))
        .andExpect(status().isUnauthorized());
  }

  /** HS256 서명 토큰. role 이 null 이면 클레임 자체를 넣지 않는다. */
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
