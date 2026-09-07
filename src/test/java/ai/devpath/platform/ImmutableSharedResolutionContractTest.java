package ai.devpath.platform;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.shared.error.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ImmutableSharedResolutionContractTest {

  private static final String VERSION = "0.0.1-rm.20260907";
  private static final String SHA_256 =
      "3a64de1a1773f1aa05ccd801a88f01ef2cead887e44930554074230fd01f2996";

  @Test
  void resolvesTheExactImmutableSharedReleaseWithoutCompositeSubstitution() throws Exception {
    Path source =
        Path.of(ErrorCode.class.getProtectionDomain().getCodeSource().getLocation().toURI());

    assertThat(source).isRegularFile().hasFileName("devpath-shared-" + VERSION + ".jar");
    assertThat(source.toString().replace('\\', '/')).doesNotContain("/shared-et9/build/");
    assertThat(sha256(source)).isEqualTo(SHA_256);
    assertThat(Files.readString(Path.of("gradle.properties")))
        .contains("devpathSharedVersion=" + VERSION)
        .doesNotContain("devpathSharedVersion=0.0.1-SNAPSHOT");
    assertThat(Files.readString(Path.of("build.gradle.kts")))
        .contains("implementation(devpathSharedCoordinate)")
        .doesNotContain("devpath-shared:0.0.1-SNAPSHOT")
        .doesNotContain("devpath-shared:0.0.1-et8.20260816");
  }

  private static String sha256(Path file) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }
}
