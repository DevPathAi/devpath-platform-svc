package ai.devpath.platform.release;

public record ReleaseOAuthIdentity(
		String candidateSpecSha256,
		String runKey,
		String email) {}
