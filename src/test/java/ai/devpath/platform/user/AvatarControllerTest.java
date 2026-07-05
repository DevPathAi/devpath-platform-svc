package ai.devpath.platform.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.platform.auth.jwt.JwtService;
import ai.devpath.shared.storage.ObjectStorage;
import ai.devpath.shared.storage.ObjectStorage.StoredObject;
import ai.devpath.shared.storage.StorageProperties;
import ai.devpath.shared.storage.StoredFileValidator;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AvatarControllerTest.TestStorageConfig.class)
class AvatarControllerTest {

  @Autowired MockMvc mvc;
  @Autowired JwtService jwt;
  @Autowired UserRepository users;
  @MockitoBean ObjectStorage storage;

  @TestConfiguration
  static class TestStorageConfig {
    @Bean
    StoredFileValidator storedFileValidator() {
      return new StoredFileValidator(
          Set.of("image/png", "image/jpeg", "image/webp"), 5L * 1024 * 1024);
    }

    @Bean
    StorageProperties storageProperties() {
      StorageProperties p = new StorageProperties();
      p.setPublicBaseUrl("http://minio:9000");
      p.setBucket("devpath");
      return p;
    }
  }

  private User newUser() {
    User u = new User();
    u.setEmail("avatar-" + System.nanoTime() + "@example.com");
    u.setNickname("아바타유저");
    u.setRole("LEARNER");
    u.setStatus("ACTIVE");
    u.setOnboardingStatus("PENDING");
    return users.save(u);
  }

  @Test
  void uploadReturnsAvatarUrl() throws Exception {
    User u = newUser();
    String token = jwt.mintAccessToken(u.getId(), "LEARNER");
    when(storage.put(any(), any(), any()))
        .thenReturn(new StoredObject("avatars/x.png", "http://minio:9000/devpath/avatars/x.png"));
    MockMultipartFile file =
        new MockMultipartFile("file", "me.png", MediaType.IMAGE_PNG_VALUE, new byte[] {1, 2, 3});

    mvc.perform(multipart("/users/me/avatar").file(file).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.avatar").value("http://minio:9000/devpath/avatars/x.png"));
  }

  @Test
  void uploadRejectsNonImageWith400() throws Exception {
    User u = newUser();
    String token = jwt.mintAccessToken(u.getId(), "LEARNER");
    MockMultipartFile file =
        new MockMultipartFile("file", "x.txt", MediaType.TEXT_PLAIN_VALUE, new byte[] {1});

    mvc.perform(multipart("/users/me/avatar").file(file).header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
  }

  @Test
  void deleteNullsAvatar() throws Exception {
    User u = newUser();
    String token = jwt.mintAccessToken(u.getId(), "LEARNER");
    mvc.perform(delete("/users/me/avatar").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.avatar").doesNotExist());
  }
}
