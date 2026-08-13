package ai.devpath.platform.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 마스킹 케이스 표 — 스펙 §6.3.
 * dp_core 의 sensitive_text_masker_test.dart 와 <b>입력·기대 출력이 완전히 같다.</b>
 * 한쪽만 고치면 두 구현이 어긋난다.
 */
class SensitiveTextMaskerTest {

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "연락처는 hong@example.com 입니다|연락처는 [EMAIL] 입니다",
      "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc|Authorization=[REDACTED]",
      "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc 만료|token [TOKEN] 만료",
      "연결 실패 jdbc:postgresql://db:5432/devpath?user=x|연결 실패 [DSN]",
      "주민번호 900101-1234567 조회|주민번호 [RRN] 조회",
      "카드 1234-5678-9012-3456 승인|카드 [CARD] 승인",
      "전화 010-1234-5678 로 연락|전화 [PHONE] 로 연락",
      "파일 C:\\Users\\deepe\\project\\a.txt 없음|파일 [PATH]\\project\\a.txt 없음",
      "경로 /home/ubuntu/app/x.log 실패|경로 [PATH]/app/x.log 실패",
      "서버 10.0.1.23 응답 없음|서버 [IP] 응답 없음",
      "정상 메시지입니다|정상 메시지입니다",
  })
  void masksBySpecTable(String input, String expected) {
    assertThat(SensitiveTextMasker.mask(input)).isEqualTo(expected);
  }

  @Test
  void emptyAndNullPassThrough() {
    assertThat(SensitiveTextMasker.mask("")).isEqualTo("");
    assertThat(SensitiveTextMasker.mask(null)).isNull();
  }

  @Test
  void truncationHappensAfterMasking() {
    // 절단이 마스킹보다 뒤여야 잘린 토큰 조각이 남지 않는다.
    String input = "메일 hong@example.com 그리고 " + "가".repeat(600);
    String out = SensitiveTextMasker.maskAndTruncate(input, 500);
    assertThat(out).hasSize(500);
    assertThat(out).startsWith("메일 [EMAIL] 그리고");
    assertThat(out).doesNotContain("hong@example.com");
  }
}
