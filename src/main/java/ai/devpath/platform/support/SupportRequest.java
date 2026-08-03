package ai.devpath.platform.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 오류 신고·문의 접수 건. plain JPA(ads/Advertisement 스타일). */
@Entity
@Table(name = "support_requests")
public class SupportRequest {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "reporter_id")
  private Long reporterId;

  /** ERROR | INQUIRY */
  private String type;

  private String title;
  private String body;

  @Column(name = "page_path")
  private String pagePath;

  @Column(name = "app_version")
  private String appVersion;

  @Column(name = "user_agent")
  private String userAgent;

  private String viewport;

  @Column(name = "trace_id")
  private String traceId;

  @Column(name = "error_code")
  private String errorCode;

  @Column(name = "occurred_at")
  private Instant occurredAt;

  /** OPEN | IN_PROGRESS | RESOLVED | WONTFIX */
  private String status = "OPEN";

  @Column(name = "admin_note")
  private String adminNote;

  @Column(name = "handled_by")
  private Long handledBy;

  @Column(name = "handled_at")
  private Instant handledAt;

  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  public Long getId() { return id; }
  public Long getReporterId() { return reporterId; }
  public void setReporterId(Long v) { this.reporterId = v; }
  public String getType() { return type; }
  public void setType(String v) { this.type = v; }
  public String getTitle() { return title; }
  public void setTitle(String v) { this.title = v; }
  public String getBody() { return body; }
  public void setBody(String v) { this.body = v; }
  public String getPagePath() { return pagePath; }
  public void setPagePath(String v) { this.pagePath = v; }
  public String getAppVersion() { return appVersion; }
  public void setAppVersion(String v) { this.appVersion = v; }
  public String getUserAgent() { return userAgent; }
  public void setUserAgent(String v) { this.userAgent = v; }
  public String getViewport() { return viewport; }
  public void setViewport(String v) { this.viewport = v; }
  public String getTraceId() { return traceId; }
  public void setTraceId(String v) { this.traceId = v; }
  public String getErrorCode() { return errorCode; }
  public void setErrorCode(String v) { this.errorCode = v; }
  public Instant getOccurredAt() { return occurredAt; }
  public void setOccurredAt(Instant v) { this.occurredAt = v; }
  public String getStatus() { return status; }
  public void setStatus(String v) { this.status = v; }
  public String getAdminNote() { return adminNote; }
  public void setAdminNote(String v) { this.adminNote = v; }
  public Long getHandledBy() { return handledBy; }
  public void setHandledBy(Long v) { this.handledBy = v; }
  public Instant getHandledAt() { return handledAt; }
  public void setHandledAt(Instant v) { this.handledAt = v; }
  public Instant getCreatedAt() { return createdAt; }
}
