package ai.devpath.platform.support;

import ai.devpath.platform.support.dto.SupportCreateRequest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
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
