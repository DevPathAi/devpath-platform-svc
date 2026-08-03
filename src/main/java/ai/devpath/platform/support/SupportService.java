package ai.devpath.platform.support;

import ai.devpath.platform.support.dto.AdminSupportDetail;
import ai.devpath.platform.support.dto.AdminSupportPage;
import ai.devpath.platform.support.dto.AdminSupportRow;
import ai.devpath.platform.support.dto.SupportCreateRequest;
import ai.devpath.platform.support.dto.SupportFailureView;
import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.error.ErrorCode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 접수 로직.
 *
 * <p><b>본문(title·body)만 엄격 검증</b>한다. 부가 정보(failures 개수·컬럼 길이)는 거절이 아니라
 * 절단으로 처리한다 — 제보는 사용자가 이미 문제를 겪은 뒤의 마지막 행동이라, 형식 문제로
 * 제보 자체를 잃게 하면 안 된다.
 */
@Service
public class SupportService {

  private static final int MAX_FAILURES = 10;
  private static final int TITLE_MAX = 200;
  private static final int BODY_MAX = 5000;
  private static final int MESSAGE_MAX = 500;
  private static final int PATH_MAX = 512;
  private static final int UA_MAX = 512;
  private static final int SHORT_MAX = 64;
  private static final int VERSION_MAX = 32;
  private static final int VIEWPORT_MAX = 32;

  private final SupportRequestRepository requests;
  private final SupportRequestFailureRepository failures;

  public SupportService(SupportRequestRepository requests,
      SupportRequestFailureRepository failures) {
    this.requests = requests;
    this.failures = failures;
  }

  @Transactional
  public SupportRequest create(long reporterId, SupportCreateRequest req) {
    String type = req.type();
    if (!"ERROR".equals(type) && !"INQUIRY".equals(type)) {
      throw new IllegalArgumentException("type must be ERROR or INQUIRY");
    }
    String title = req.title() == null ? "" : req.title().trim();
    String body = req.body() == null ? "" : req.body().trim();
    if (title.isEmpty() || title.length() > TITLE_MAX) {
      throw new IllegalArgumentException("title must be 1-" + TITLE_MAX + " characters");
    }
    if (body.isEmpty() || body.length() > BODY_MAX) {
      throw new IllegalArgumentException("body must be 1-" + BODY_MAX + " characters");
    }

    SupportCreateRequest.Context ctx = req.context();
    SupportRequest saved = new SupportRequest();
    saved.setReporterId(reporterId);
    saved.setType(type);
    saved.setTitle(SensitiveTextMasker.mask(title));
    saved.setBody(SensitiveTextMasker.mask(body));
    saved.setStatus("OPEN");
    if (ctx != null) {
      saved.setPagePath(cut(SensitiveTextMasker.mask(stripQuery(ctx.pagePath())), PATH_MAX));
      saved.setAppVersion(cut(ctx.appVersion(), VERSION_MAX));
      saved.setUserAgent(cut(ctx.userAgent(), UA_MAX));
      saved.setViewport(cut(ctx.viewport(), VIEWPORT_MAX));
      saved.setTraceId(cut(ctx.traceId(), SHORT_MAX));
      saved.setErrorCode(cut(ctx.errorCode(), SHORT_MAX));
      saved.setOccurredAt(parseOrNull(ctx.occurredAt()));
    }
    requests.save(saved);

    if (ctx != null && ctx.failures() != null) {
      List<SupportCreateRequest.Failure> list = ctx.failures();
      int n = Math.min(list.size(), MAX_FAILURES);
      for (int i = 0; i < n; i++) {
        SupportCreateRequest.Failure f = list.get(i);
        SupportRequestFailure row = new SupportRequestFailure();
        row.setRequestId(saved.getId());
        row.setSeq((short) i);
        row.setMethod(cut(f.method() == null ? "GET" : f.method(), 8));
        row.setPath(cut(stripQuery(f.path() == null ? "" : f.path()), PATH_MAX));
        row.setStatusCode(f.statusCode() == null ? null : f.statusCode().shortValue());
        row.setErrorCode(cut(f.errorCode(), SHORT_MAX));
        row.setTraceId(cut(f.traceId(), SHORT_MAX));
        // 서버 재마스킹 — 조작된 클라이언트가 원문을 밀어넣어도 원문이 저장되지 않는다.
        row.setMessage(SensitiveTextMasker.maskAndTruncate(f.message(), MESSAGE_MAX));
        Instant at = parseOrNull(f.occurredAt());
        row.setOccurredAt(at == null ? Instant.now() : at);
        failures.save(row);
      }
    }
    return saved;
  }

  /** 쿼리스트링 제거 — 클라가 이미 빼지만 서버도 보장한다. */
  private static String stripQuery(String path) {
    if (path == null) {
      return null;
    }
    int q = path.indexOf('?');
    return q < 0 ? path : path.substring(0, q);
  }

  private static String cut(String s, int max) {
    if (s == null) {
      return null;
    }
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static final int LIMIT_MAX = 100;
  private static final List<String> STATUSES =
      List.of("OPEN", "IN_PROGRESS", "RESOLVED", "WONTFIX");

  /**
   * keyset 목록 — id 내림차순(최신순). cursor 가 없으면 처음부터, 있으면 id &lt; cursor 만.
   * nextCursor 는 <b>꽉 찬 페이지일 때만</b> 마지막 행 id, 아니면 null.
   */
  @Transactional(readOnly = true)
  public AdminSupportPage list(String status, String type, String cursor, int limit) {
    int size = Math.min(Math.max(limit, 1), LIMIT_MAX);
    long before = (cursor == null || cursor.isBlank()) ? Long.MAX_VALUE : Long.parseLong(cursor);
    var pageable = PageRequest.of(0, size);

    boolean hasStatus = status != null && !status.isBlank();
    boolean hasType = type != null && !type.isBlank();
    List<SupportRequest> rows;
    if (hasStatus && hasType) {
      rows = requests.findByStatusAndTypeAndIdLessThanOrderByIdDesc(status, type, before, pageable);
    } else if (hasStatus) {
      rows = requests.findByStatusAndIdLessThanOrderByIdDesc(status, before, pageable);
    } else if (hasType) {
      rows = requests.findByTypeAndIdLessThanOrderByIdDesc(type, before, pageable);
    } else {
      rows = requests.findByIdLessThanOrderByIdDesc(before, pageable);
    }

    String nextCursor = (rows.size() == size)
        ? String.valueOf(rows.get(rows.size() - 1).getId())
        : null;

    List<AdminSupportRow> data = rows.stream()
        .map(r -> new AdminSupportRow(
            r.getId(), r.getType(), r.getTitle(), r.getStatus(), r.getPagePath(),
            r.getReporterId(), failures.countByRequestId(r.getId()), iso(r.getCreatedAt())))
        .toList();
    return new AdminSupportPage(data, nextCursor, size);
  }

  @Transactional(readOnly = true)
  public AdminSupportDetail detail(long id) {
    return toDetail(find(id));
  }

  /**
   * 상태 전이. handled_by = 관리자 id, handled_at = now.
   * OPEN 으로 되돌리면 둘 다 NULL 로 초기화한다(처리 이력이 없는 상태로 복귀).
   */
  @Transactional
  public AdminSupportDetail updateStatus(long id, long adminId, String status, String adminNote) {
    if (status == null || !STATUSES.contains(status)) {
      throw new IllegalArgumentException("status must be one of " + STATUSES);
    }
    SupportRequest r = find(id);
    r.setStatus(status);
    if (adminNote != null) {
      r.setAdminNote(adminNote);
    }
    if ("OPEN".equals(status)) {
      r.setHandledBy(null);
      r.setHandledAt(null);
    } else {
      r.setHandledBy(adminId);
      r.setHandledAt(Instant.now());
    }
    requests.save(r);
    return toDetail(r);
  }

  private SupportRequest find(long id) {
    return requests.findById(id).orElseThrow(() ->
        new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "support request not found: " + id));
  }

  private AdminSupportDetail toDetail(SupportRequest r) {
    List<SupportFailureView> rows = failures.findByRequestIdOrderBySeqAsc(r.getId()).stream()
        .map(f -> new SupportFailureView(f.getSeq(), f.getMethod(), f.getPath(), f.getStatusCode(),
            f.getErrorCode(), f.getTraceId(), f.getMessage(), iso(f.getOccurredAt())))
        .toList();
    return new AdminSupportDetail(
        r.getId(), r.getType(), r.getTitle(), r.getBody(), r.getStatus(), r.getPagePath(),
        r.getAppVersion(), r.getUserAgent(), r.getViewport(), r.getTraceId(), r.getErrorCode(),
        iso(r.getOccurredAt()), r.getReporterId(), r.getAdminNote(), r.getHandledBy(),
        iso(r.getHandledAt()), iso(r.getCreatedAt()), rows);
  }

  /** 날짜는 String ISO-8601 로 내보낸다(요청 계약과 대칭). */
  private static String iso(Instant at) {
    return at == null ? null : at.toString();
  }

  /** 파싱 실패는 null 로 흡수한다 — 부가 정보의 형식 문제로 제보를 잃지 않는다. */
  private static Instant parseOrNull(String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(iso);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
