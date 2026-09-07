package ai.devpath.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("devpath.mentor-access")
public class MentorAccessProperties {
  private String inviteCodeHmacSecret;
  private boolean batchEnabled;
  private String batchCron = "0 0 10 * * *";
  private String batchZone = "Asia/Seoul";
  private int batchChunkSize = 25;
  private int batchDailyCap = 100;

  public String getInviteCodeHmacSecret() { return inviteCodeHmacSecret; }
  public void setInviteCodeHmacSecret(String value) { inviteCodeHmacSecret = value; }
  public boolean isBatchEnabled() { return batchEnabled; }
  public void setBatchEnabled(boolean value) { batchEnabled = value; }
  public String getBatchCron() { return batchCron; }
  public void setBatchCron(String value) { batchCron = value; }
  public String getBatchZone() { return batchZone; }
  public void setBatchZone(String value) { batchZone = value; }
  public int getBatchChunkSize() { return batchChunkSize; }
  public void setBatchChunkSize(int value) { batchChunkSize = value; }
  public int getBatchDailyCap() { return batchDailyCap; }
  public void setBatchDailyCap(int value) { batchDailyCap = value; }
}
