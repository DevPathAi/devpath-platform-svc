package ai.devpath.platform.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 제보에 첨부된 최근 API 실패 1건. 부모 참조는 <b>plain Long 컬럼</b>이다 —
 * {@code @OneToMany} cascade 대신 저장·조회를 명시적으로 두어 테스트를 단순하게 유지한다.
 * FK 와 ON DELETE CASCADE 는 DB 제약이 담당한다.
 */
@Entity
@Table(name = "support_request_failures")
public class SupportRequestFailure {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "request_id")
  private Long requestId;

  /** 0 = 가장 최근. (request_id, seq) UNIQUE. */
  private short seq;

  private String method;
  private String path;

  /** 네트워크 실패면 null — 이 구분 자체가 진단 정보다. */
  @Column(name = "status_code")
  private Short statusCode;

  @Column(name = "error_code")
  private String errorCode;

  @Column(name = "trace_id")
  private String traceId;

  private String message;

  @Column(name = "occurred_at")
  private Instant occurredAt;

  public Long getId() { return id; }
  public Long getRequestId() { return requestId; }
  public void setRequestId(Long v) { this.requestId = v; }
  public short getSeq() { return seq; }
  public void setSeq(short v) { this.seq = v; }
  public String getMethod() { return method; }
  public void setMethod(String v) { this.method = v; }
  public String getPath() { return path; }
  public void setPath(String v) { this.path = v; }
  public Short getStatusCode() { return statusCode; }
  public void setStatusCode(Short v) { this.statusCode = v; }
  public String getErrorCode() { return errorCode; }
  public void setErrorCode(String v) { this.errorCode = v; }
  public String getTraceId() { return traceId; }
  public void setTraceId(String v) { this.traceId = v; }
  public String getMessage() { return message; }
  public void setMessage(String v) { this.message = v; }
  public Instant getOccurredAt() { return occurredAt; }
  public void setOccurredAt(Instant v) { this.occurredAt = v; }
}
