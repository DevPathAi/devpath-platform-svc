package ai.devpath.platform.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("devpath.beta")
public class BetaProperties {
    private List<String> adminEmails = List.of();
    private String notifyEmail;
    private String pendingRedirect = "/login?beta=pending";
    private Duration statusTtl = Duration.ofMinutes(30);

    public List<String> getAdminEmails() { return adminEmails; }
    public void setAdminEmails(List<String> v) { this.adminEmails = v; }
    public String getNotifyEmail() { return notifyEmail; }
    public void setNotifyEmail(String v) { this.notifyEmail = v; }
    public String getPendingRedirect() { return pendingRedirect; }
    public void setPendingRedirect(String v) { this.pendingRedirect = v; }
    public Duration getStatusTtl() { return statusTtl; }
    public void setStatusTtl(Duration v) { this.statusTtl = v; }
}
