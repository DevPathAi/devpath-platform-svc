package ai.devpath.platform.release;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ReleaseOAuthProviderService {
	public record Token(String accessToken, String tokenType, long expiresIn) {}

	private final ReleaseControlProperties properties;
	private final ReleaseStateStore state;
	private final ReleaseControlService control;
	private final Supplier<String> codes;
	private final Supplier<String> tokens;

	@Autowired
	public ReleaseOAuthProviderService(
			ReleaseControlProperties properties,
			ReleaseStateStore state,
			ReleaseControlService control) {
		this(properties, state, control, ReleaseTokens::random, ReleaseTokens::random);
	}

	ReleaseOAuthProviderService(
			ReleaseControlProperties properties,
			ReleaseStateStore state,
			ReleaseControlService control,
			Supplier<String> codes,
			Supplier<String> tokens) {
		this.properties = properties;
		this.state = state;
		this.control = control;
		this.codes = codes;
		this.tokens = tokens;
	}

	public URI authorize(
			String candidateSpecSha256,
			String runKey,
			String clientId,
			String oauthState) {
		if (!constantEquals(properties.getOauthClientId(), clientId)) {
			throw new ReleaseControlException("OAuth client is invalid");
		}
		if (oauthState == null || oauthState.isBlank() || oauthState.length() > 512) {
			throw new ReleaseControlException("OAuth state is invalid");
		}
		ReleaseRunState run = control.requireRun(candidateSpecSha256, runKey, null);
		String code = codes.get();
		ReleaseOAuthIdentity identity = new ReleaseOAuthIdentity(
			candidateSpecSha256, runKey, run.fixtureEmail());
		state.saveAuthorizationCode(code, identity, properties.getOauthCodeTtl());
		control.markOauthIssued(candidateSpecSha256, runKey);
		return URI.create(properties.getOauthRedirectUri()
			+ "?code=" + encode(code)
			+ "&state=" + encode(oauthState));
	}

	public Token exchange(String code, String clientId, String clientSecret, String redirectUri) {
		if (!constantEquals(properties.getOauthClientId(), clientId)
				|| !constantEquals(properties.getOauthClientSecret(), clientSecret)) {
			throw new ReleaseControlException(
				ReleaseControlException.Kind.UNAUTHORIZED, "OAuth client authentication failed");
		}
		if (!properties.getOauthRedirectUri().equals(redirectUri)) {
			throw new ReleaseControlException("OAuth redirect URI is invalid");
		}
		ReleaseOAuthIdentity identity = state.consumeAuthorizationCode(code)
			.orElseThrow(() -> new ReleaseControlException("OAuth code is invalid or already consumed"));
		String accessToken = tokens.get();
		state.saveAccessToken(accessToken, identity, properties.getOauthAccessTtl());
		control.markOauthExchanged(identity.candidateSpecSha256(), identity.runKey());
		return new Token(accessToken, "Bearer", properties.getOauthAccessTtl().toSeconds());
	}

	public Map<String, Object> userInfo(String accessToken) {
		ReleaseOAuthIdentity identity = state.consumeAccessToken(accessToken)
			.orElseThrow(() -> new ReleaseControlException(
				ReleaseControlException.Kind.UNAUTHORIZED,
				"OAuth access token is invalid or already consumed"));
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("id", identity.email());
		value.put("login", "release-fixture");
		value.put("name", "Release Fixture");
		value.put("email", identity.email());
		return Map.copyOf(value);
	}

	private static boolean constantEquals(String expected, String actual) {
		byte[] left = expected == null ? new byte[0] : expected.getBytes(StandardCharsets.UTF_8);
		byte[] right = actual == null ? new byte[0] : actual.getBytes(StandardCharsets.UTF_8);
		return left.length > 0 && MessageDigest.isEqual(left, right);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
