package ai.devpath.platform.release;

import java.time.Duration;
import java.util.Optional;

public interface ReleaseStateStore {
	Optional<ReleaseRunState> findRun(String candidateSpecSha256, String runKey);
	void saveRun(ReleaseRunState value, Duration ttl);
	void saveAuthorizationCode(String code, ReleaseOAuthIdentity identity, Duration ttl);
	Optional<ReleaseOAuthIdentity> consumeAuthorizationCode(String code);
	void saveAccessToken(String token, ReleaseOAuthIdentity identity, Duration ttl);
	Optional<ReleaseOAuthIdentity> consumeAccessToken(String token);
}
