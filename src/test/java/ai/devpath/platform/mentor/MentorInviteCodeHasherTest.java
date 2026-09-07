package ai.devpath.platform.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.devpath.platform.config.MentorAccessProperties;
import org.junit.jupiter.api.Test;

class MentorInviteCodeHasherTest {
  @Test
  void hashesRawCodeDeterministicallyWithoutEmbeddingIt() {
    MentorAccessProperties properties = new MentorAccessProperties();
    properties.setInviteCodeHmacSecret("test-invite-code-hmac-secret-32-bytes");
    MentorInviteCodeHasher hasher = new MentorInviteCodeHasher(properties);

    String hash = hasher.hash("Judge Secret Code");

    assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(hash).isEqualTo(hasher.hash("Judge Secret Code"));
    assertThat(hash).doesNotContain("Judge", "Secret", "Code");
  }

  @Test
  void refusesShortOperationalSecret() {
    MentorAccessProperties properties = new MentorAccessProperties();
    properties.setInviteCodeHmacSecret("short");
    assertThatThrownBy(() -> new MentorInviteCodeHasher(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MENTOR_INVITE_CODE_HMAC_SECRET");
  }
}
