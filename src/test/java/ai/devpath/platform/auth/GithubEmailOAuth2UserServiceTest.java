package ai.devpath.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 순수 단위 테스트: /user/emails 응답에서 primary+verified 이메일을 선택하는 로직을 검증한다.
 *
 * <p>loadUser(...)는 상위 DefaultOAuth2UserService.super.loadUser가 실제 HTTP를 수행하므로 순수 단위로 구동할 수 없다.
 * 대신 이메일 선택 규칙을 담은 private fetchPrimaryVerifiedEmail를 리플렉션으로 호출하고, 내부 RestClient 필드를 mock으로 교체해
 * GitHub /user/emails 호출을 격리한다.
 */
class GithubEmailOAuth2UserServiceTest {

  private GithubEmailOAuth2UserService service;
  private RestClient rest;

  @SuppressWarnings("rawtypes")
  private RestClient.RequestHeadersUriSpec uriSpec;

  @BeforeEach
  void setup() throws Exception {
    service = new GithubEmailOAuth2UserService();
    rest = mock(RestClient.class);
    uriSpec = mock(RestClient.RequestHeadersUriSpec.class);

    Field restField = GithubEmailOAuth2UserService.class.getDeclaredField("rest");
    restField.setAccessible(true);
    restField.set(service, rest);
  }

  @SuppressWarnings("unchecked")
  private void stubEmailsResponse(List<Map<String, Object>> body) {
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
    when(rest.get()).thenReturn(uriSpec);
    when(uriSpec.uri(anyString())).thenReturn(uriSpec);
    when(uriSpec.header(eq("Authorization"), any(String[].class))).thenReturn(uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(List.class)).thenReturn(body);
  }

  private String invokeFetch() throws Exception {
    Method m =
        GithubEmailOAuth2UserService.class.getDeclaredMethod(
            "fetchPrimaryVerifiedEmail", String.class);
    m.setAccessible(true);
    return (String) m.invoke(service, "token-abc");
  }

  private static Map<String, Object> email(String addr, boolean primary, boolean verified) {
    return Map.of("email", addr, "primary", primary, "verified", verified);
  }

  @Test
  void selectsPrimaryAndVerifiedEmail() throws Exception {
    stubEmailsResponse(
        List.of(
            email("secondary@x.com", false, true),
            email("primary@x.com", true, true),
            email("other@x.com", false, false)));

    assertThat(invokeFetch()).isEqualTo("primary@x.com");
  }

  @Test
  void returnsNullWhenPrimaryNotVerified() throws Exception {
    stubEmailsResponse(
        List.of(
            email("primary@x.com", true, false), // primary but not verified
            email("verified@x.com", false, true))); // verified but not primary

    assertThat(invokeFetch()).isNull();
  }

  @Test
  void returnsNullWhenNoPrimary() throws Exception {
    stubEmailsResponse(List.of(email("a@x.com", false, true), email("b@x.com", false, true)));

    assertThat(invokeFetch()).isNull();
  }

  @Test
  void returnsNullWhenBodyNull() throws Exception {
    stubEmailsResponse(null);

    assertThat(invokeFetch()).isNull();
  }

  @Test
  void returnsNullWhenEmpty() throws Exception {
    stubEmailsResponse(List.of());

    assertThat(invokeFetch()).isNull();
  }
}
