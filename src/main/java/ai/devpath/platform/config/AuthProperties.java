package ai.devpath.platform.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("devpath.auth")
public class AuthProperties {
	private String jwtSecret;
	private Duration accessTtl = Duration.ofMinutes(15);
	private Duration refreshTtl = Duration.ofDays(14);
	private Duration authCodeTtl = Duration.ofSeconds(60);
	private String webUrl;
	private String mobileRedirectUri = "devpath://callback";
	private String cookieDomain = "";
	private boolean cookieSecure = false;
	private String cookieSameSite = "Lax";

	public String getJwtSecret() { return jwtSecret; }
	public void setJwtSecret(String v) { this.jwtSecret = v; }
	public Duration getAccessTtl() { return accessTtl; }
	public void setAccessTtl(Duration v) { this.accessTtl = v; }
	public Duration getRefreshTtl() { return refreshTtl; }
	public void setRefreshTtl(Duration v) { this.refreshTtl = v; }
	public Duration getAuthCodeTtl() { return authCodeTtl; }
	public void setAuthCodeTtl(Duration v) { this.authCodeTtl = v; }
	public String getWebUrl() { return webUrl; }
	public void setWebUrl(String v) { this.webUrl = v; }
	public String getMobileRedirectUri() { return mobileRedirectUri; }
	public void setMobileRedirectUri(String v) { this.mobileRedirectUri = v; }
	public String getCookieDomain() { return cookieDomain; }
	public void setCookieDomain(String v) { this.cookieDomain = v; }
	public boolean isCookieSecure() { return cookieSecure; }
	public void setCookieSecure(boolean v) { this.cookieSecure = v; }
	public String getCookieSameSite() { return cookieSameSite; }
	public void setCookieSameSite(String v) { this.cookieSameSite = v; }
}
