package ai.devpath.platform.release;

import java.util.List;
import java.util.Set;

public record ReleaseRunState(
		String candidateSpecSha256,
		String runKey,
		String journey,
		String fixtureRevision,
		String fixtureEmail,
		boolean analyticsPermission,
		List<ReleaseAnalyticsEvent> analyticsEvents,
		Set<String> commands,
		boolean oauthIssued,
		boolean oauthExchanged) {

	public ReleaseRunState {
		analyticsEvents = List.copyOf(analyticsEvents);
		commands = Set.copyOf(commands);
	}

	public ReleaseRunState withAnalyticsPermission() {
		return new ReleaseRunState(candidateSpecSha256, runKey, journey, fixtureRevision,
			fixtureEmail, true, analyticsEvents, commands, oauthIssued, oauthExchanged);
	}

	public ReleaseRunState withAnalyticsEvent(ReleaseAnalyticsEvent event) {
		var next = new java.util.ArrayList<>(analyticsEvents);
		next.add(event);
		return new ReleaseRunState(candidateSpecSha256, runKey, journey, fixtureRevision,
			fixtureEmail, analyticsPermission, next, commands, oauthIssued, oauthExchanged);
	}

	public ReleaseRunState withCommand(String command) {
		var next = new java.util.LinkedHashSet<>(commands);
		next.add(command);
		return new ReleaseRunState(candidateSpecSha256, runKey, journey, fixtureRevision,
			fixtureEmail, analyticsPermission, analyticsEvents, next, oauthIssued, oauthExchanged);
	}

	public ReleaseRunState withOauthIssued() {
		return new ReleaseRunState(candidateSpecSha256, runKey, journey, fixtureRevision,
			fixtureEmail, analyticsPermission, analyticsEvents, commands, true, oauthExchanged);
	}

	public ReleaseRunState withOauthExchanged() {
		return new ReleaseRunState(candidateSpecSha256, runKey, journey, fixtureRevision,
			fixtureEmail, analyticsPermission, analyticsEvents, commands, oauthIssued, true);
	}
}
