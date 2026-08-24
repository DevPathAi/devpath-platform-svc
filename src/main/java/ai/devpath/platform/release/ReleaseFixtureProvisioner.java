package ai.devpath.platform.release;

public interface ReleaseFixtureProvisioner {
	void provision(String candidateSpecSha256, String runKey, String journey, String email);
}
