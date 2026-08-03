package ai.devpath.platform.support;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 민감 패턴 마스킹 — 스펙 §6.2. 규칙 순서가 결과를 결정하므로 <b>순서를 바꾸지 않는다.</b>
 *
 * <p>dp_core 의 {@code SensitiveTextMasker}(Dart)와 같은 규칙·같은 순서다. 한쪽만 고치면
 * 두 구현이 어긋나고, 스펙 §6.3 케이스 표 테스트가 그 어긋남을 잡는다.
 *
 * <p>패턴 문자열에 {@code (?i)} 인라인 플래그와 lookbehind 를 쓰지 않는다 —
 * Dart {@code RegExp} 가 지원하지 않아 두 구현을 같은 문자열로 유지할 수 없게 된다.
 */
public final class SensitiveTextMasker {

  private SensitiveTextMasker() {}

  private record Rule(Pattern pattern, String replacement) {}

  private static final List<Rule> RULES = List.of(
      // 1. 키=값 형태 비밀. 규칙 2보다 먼저다 — 반대면 "Authorization=[REDACTED] [TOKEN]" 이 남는다.
      //    값 패턴의 (Bearer\s+)? 도 같은 이유(헤더 값이 두 토큰이라 \S+ 하나로는 본체가 남는다).
      new Rule(Pattern.compile(
          "(api[_-]?key|authorization|password|secret|token)\\s*[:=]\\s*(Bearer\\s+)?[^\\s,;]+",
          Pattern.CASE_INSENSITIVE), "$1=[REDACTED]"),
      // 2. 키 없이 노출된 JWT
      new Rule(Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*"), "[TOKEN]"),
      // 3. DB 접속 문자열. 이메일·IP 보다 먼저 — 통째로 지워야 호스트·계정 흔적이 안 남는다.
      new Rule(Pattern.compile("(jdbc:|postgresql://|postgres://|mysql://|redis://)\\S+"), "[DSN]"),
      // 4. 이메일
      new Rule(Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+"), "[EMAIL]"),
      // 5. 카드(16자리). 주민번호(13자리)보다 먼저 — 반대면 구분자 없는 16자리의 중간
      //    13자리가 RRN 으로 잡혀 카드번호를 조각낸다.
      new Rule(Pattern.compile("\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}"), "[CARD]"),
      // 6. 주민등록번호
      new Rule(Pattern.compile("\\d{6}-?[1-4]\\d{6}"), "[RRN]"),
      // 7. 휴대전화
      new Rule(Pattern.compile("01[016789]-?\\d{3,4}-?\\d{4}"), "[PHONE]"),
      // 8. 윈도 홈 경로 — 사용자명까지만 지우고 하위 경로는 진단용으로 남긴다.
      new Rule(Pattern.compile("[A-Za-z]:\\\\Users\\\\[^\\\\\\s]+"), "[PATH]"),
      // 9. POSIX 홈 경로 — 같은 이유.
      new Rule(Pattern.compile("/(home|Users)/[^/\\s]+"), "[PATH]"),
      // 10. IPv4. 마지막이다 — 앞 규칙이 끝난 뒤 남은 것만 보게 해 오탐을 줄인다.
      new Rule(Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b"), "[IP]"));

  /** null·빈 문자열은 그대로 통과한다. */
  public static String mask(String input) {
    if (input == null || input.isEmpty()) {
      return input;
    }
    String out = input;
    for (Rule r : RULES) {
      out = r.pattern().matcher(out).replaceAll(r.replacement());
    }
    return out;
  }

  /** 마스킹 후 절단. 절단이 뒤여야 잘린 토큰 조각이 남지 않는다. */
  public static String maskAndTruncate(String input, int max) {
    String masked = mask(input);
    if (masked == null || masked.length() <= max) {
      return masked;
    }
    return masked.substring(0, max);
  }
}
