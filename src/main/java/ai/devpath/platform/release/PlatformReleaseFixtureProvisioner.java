package ai.devpath.platform.release;

import ai.devpath.platform.beta.BetaAllowlist;
import ai.devpath.platform.beta.BetaAllowlistRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PlatformReleaseFixtureProvisioner implements ReleaseFixtureProvisioner {
	private final BetaAllowlistRepository allowlist;

	public PlatformReleaseFixtureProvisioner(BetaAllowlistRepository allowlist) {
		this.allowlist = allowlist;
	}

	@Override
	@Transactional
	public void provision(String candidateSpecSha256, String runKey, String journey, String email) {
		if (allowlist.existsByEmail(email)) return;
		BetaAllowlist row = new BetaAllowlist();
		row.setEmail(email);
		row.setNote("mission-spine deterministic staging fixture");
		row.setAddedBy("mission-spine-release-control");
		allowlist.save(row);
	}
}
