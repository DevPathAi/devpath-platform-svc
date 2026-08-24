package ai.devpath.platform.release;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/release/oauth")
public class ReleaseOAuthProviderController {
	private final ReleaseOAuthProviderService oauth;
	private final ReleaseControlProperties properties;

	public ReleaseOAuthProviderController(
			ReleaseOAuthProviderService oauth,
			ReleaseControlProperties properties) {
		this.oauth = oauth;
		this.properties = properties;
	}

	@GetMapping("/authorize")
	public ResponseEntity<Void> authorize(
			@RequestHeader(ReleaseHttp.CANDIDATE_HEADER) String candidate,
			@RequestHeader(ReleaseHttp.RUN_HEADER) String runKey,
			@RequestParam("response_type") String responseType,
			@RequestParam("client_id") String clientId,
			@RequestParam("redirect_uri") String redirectUri,
			@RequestParam String state) {
		if (!"code".equals(responseType) || !properties.getOauthRedirectUri().equals(redirectUri)) {
			throw new ReleaseControlException("OAuth authorization request is invalid");
		}
		return ResponseEntity.status(302)
			.location(oauth.authorize(candidate, runKey, clientId, state))
			.build();
	}

	@PostMapping("/token")
	public Map<String, Object> token(
			@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestParam("grant_type") String grantType,
			@RequestParam String code,
			@RequestParam("redirect_uri") String redirectUri,
			@RequestParam(name = "client_id", required = false) String formClientId,
			@RequestParam(name = "client_secret", required = false) String formClientSecret) {
		if (!"authorization_code".equals(grantType)) {
			throw new ReleaseControlException("OAuth grant type is invalid");
		}
		String[] client = clientCredentials(authorization, formClientId, formClientSecret);
		var value = oauth.exchange(code, client[0], client[1], redirectUri);
		return Map.of(
			"access_token", value.accessToken(),
			"token_type", value.tokenType(),
			"expires_in", value.expiresIn());
	}

	@GetMapping("/userinfo")
	public Map<String, Object> userInfo(
			@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		return oauth.userInfo(ReleaseHttp.bearer(authorization));
	}

	private static String[] clientCredentials(
			String authorization,
			String formClientId,
			String formClientSecret) {
		if (authorization == null || authorization.isBlank()) {
			return new String[] { formClientId, formClientSecret };
		}
		if (!authorization.startsWith("Basic ")) {
			throw new ReleaseControlException(
				ReleaseControlException.Kind.UNAUTHORIZED, "OAuth client authentication is invalid");
		}
		try {
			String decoded = new String(Base64.getDecoder().decode(
				authorization.substring("Basic ".length())), StandardCharsets.UTF_8);
			int separator = decoded.indexOf(':');
			if (separator <= 0) throw new IllegalArgumentException();
			return new String[] { decoded.substring(0, separator), decoded.substring(separator + 1) };
		} catch (IllegalArgumentException exception) {
			throw new ReleaseControlException(
				ReleaseControlException.Kind.UNAUTHORIZED, "OAuth client authentication is invalid");
		}
	}
}
