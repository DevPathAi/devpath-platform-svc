package ai.devpath.platform.release;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("devpath.release")
public class ReleaseControlProperties {
	private boolean enabled;
	private String controlToken = "";
	private String fixtureRevision = "";
	private String oauthClientId = "disabled-release-client";
	private String oauthClientSecret = "disabled-release-secret";
	private String oauthRedirectUri = "https://api.leva.ai.kr/login/oauth2/code/release";
	private String analyticsOrigin = "https://analytics-spy.staging.leva.ai.kr";
	private String sandboxOrigin = "";
	private String aiOrigin = "";
	private String lcsOrigin = "";
	private String learningOrigin = "";
	private String internalToken = "";
	private Duration runTtl = Duration.ofMinutes(45);
	private Duration oauthCodeTtl = Duration.ofMinutes(1);
	private Duration oauthAccessTtl = Duration.ofMinutes(5);
	private List<String> capabilities = new ArrayList<>();

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean value) { enabled = value; }
	public String getControlToken() { return controlToken; }
	public void setControlToken(String value) { controlToken = value; }
	public String getFixtureRevision() { return fixtureRevision; }
	public void setFixtureRevision(String value) { fixtureRevision = value; }
	public String getOauthClientId() { return oauthClientId; }
	public void setOauthClientId(String value) { oauthClientId = value; }
	public String getOauthClientSecret() { return oauthClientSecret; }
	public void setOauthClientSecret(String value) { oauthClientSecret = value; }
	public String getOauthRedirectUri() { return oauthRedirectUri; }
	public void setOauthRedirectUri(String value) { oauthRedirectUri = value; }
	public String getAnalyticsOrigin() { return analyticsOrigin; }
	public void setAnalyticsOrigin(String value) { analyticsOrigin = value; }
	public String getSandboxOrigin() { return sandboxOrigin; }
	public void setSandboxOrigin(String value) { sandboxOrigin = value; }
	public String getAiOrigin() { return aiOrigin; }
	public void setAiOrigin(String value) { aiOrigin = value; }
	public String getLcsOrigin() { return lcsOrigin; }
	public void setLcsOrigin(String value) { lcsOrigin = value; }
	public String getLearningOrigin() { return learningOrigin; }
	public void setLearningOrigin(String value) { learningOrigin = value; }
	public String getInternalToken() { return internalToken; }
	public void setInternalToken(String value) { internalToken = value; }
	public Duration getRunTtl() { return runTtl; }
	public void setRunTtl(Duration value) { runTtl = value; }
	public Duration getOauthCodeTtl() { return oauthCodeTtl; }
	public void setOauthCodeTtl(Duration value) { oauthCodeTtl = value; }
	public Duration getOauthAccessTtl() { return oauthAccessTtl; }
	public void setOauthAccessTtl(Duration value) { oauthAccessTtl = value; }
	public List<String> getCapabilities() { return capabilities; }
	public void setCapabilities(List<String> value) {
		capabilities = value == null ? new ArrayList<>() : new ArrayList<>(value);
	}
}
