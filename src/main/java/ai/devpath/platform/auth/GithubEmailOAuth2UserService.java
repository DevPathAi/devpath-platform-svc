package ai.devpath.platform.auth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** GitHub 로그인 시 userinfo에 email이 없으면 /user/emails에서 primary+verified를 보강한다. */
@Component
public class GithubEmailOAuth2UserService extends DefaultOAuth2UserService {

  private final RestClient rest = RestClient.create();

  @Override
  public OAuth2User loadUser(OAuth2UserRequest req) throws OAuth2AuthenticationException {
    OAuth2User user = super.loadUser(req);
    if (!"github".equals(req.getClientRegistration().getRegistrationId())) {
      return user;
    }
    if (user.getAttribute("email") != null) {
      return user;
    }
    String email = fetchPrimaryVerifiedEmail(req.getAccessToken().getTokenValue());
    if (email == null) {
      return user; // 이메일 미확보 → registerOrFind가 거부
    }
    Map<String, Object> attrs = new HashMap<>(user.getAttributes());
    attrs.put("email", email);
    String nameKey =
        req.getClientRegistration()
            .getProviderDetails()
            .getUserInfoEndpoint()
            .getUserNameAttributeName();
    return new DefaultOAuth2User(user.getAuthorities(), attrs, nameKey);
  }

  @SuppressWarnings("unchecked")
  private String fetchPrimaryVerifiedEmail(String accessToken) {
    List<Map<String, Object>> emails =
        rest.get()
            .uri("https://api.github.com/user/emails")
            .header("Authorization", "Bearer " + accessToken)
            .retrieve()
            .body(List.class);
    if (emails == null) {
      return null;
    }
    return emails.stream()
        .filter(e -> Boolean.TRUE.equals(e.get("primary")) && Boolean.TRUE.equals(e.get("verified")))
        .map(e -> (String) e.get("email"))
        .findFirst()
        .orElse(null);
  }
}
