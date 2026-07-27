package ai.devpath.platform.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.platform.user.dto.ProfileView;
import ai.devpath.shared.storage.ObjectStorage;
import ai.devpath.shared.storage.ObjectStorage.StoredObject;
import ai.devpath.shared.storage.StorageException;
import ai.devpath.shared.storage.StorageProperties;
import ai.devpath.shared.storage.StoredFileValidator;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AvatarServiceTest {

  private ObjectStorage storage;
  private UserProfileRepository profiles;
  private StorageProperties props;
  private AvatarService service;

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> providerOf(T bean) {
    ObjectProvider<T> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(bean);
    return p;
  }

  @BeforeEach
  void setup() {
    storage = mock(ObjectStorage.class);
    profiles = mock(UserProfileRepository.class);
    StoredFileValidator validator =
        new StoredFileValidator(Set.of("image/png"), 5L * 1024 * 1024);
    props = new StorageProperties();
    props.setPublicBaseUrl("http://minio:9000");
    props.setBucket("devpath");
    service =
        new AvatarService(
            providerOf(storage), providerOf(validator), providerOf(props), profiles);
    when(profiles.save(any(UserProfile.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void uploadStoresUrlAndReturnsProfile() {
    when(profiles.findById(1L)).thenReturn(Optional.empty());
    when(storage.put(any(), any(), eq("image/png")))
        .thenReturn(new StoredObject("avatars/x.png", "http://minio:9000/devpath/avatars/x.png"));

    ProfileView view = service.upload(1L, new byte[] {1, 2, 3}, "image/png", "me.png");

    assertThat(view.avatar()).isEqualTo("http://minio:9000/devpath/avatars/x.png");
    verify(storage).put(any(), any(), eq("image/png"));
  }

  @Test
  void uploadRejectsDisallowedType() {
    when(profiles.findById(1L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.upload(1L, new byte[] {1}, "text/plain", "x.txt"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void uploadThrows503WhenStorageMissing() {
    AvatarService noStorage =
        new AvatarService(providerOf(null), providerOf(null), providerOf(props), profiles);
    assertThatThrownBy(() -> noStorage.upload(1L, new byte[] {1}, "image/png", "x.png"))
        .isInstanceOf(StorageException.class);
  }

  @Test
  void deleteRemovesObjectAndNullsAvatar() {
    UserProfile p = new UserProfile();
    p.setUserId(1L);
    p.setAvatar("http://minio:9000/devpath/avatars/old.png");
    when(profiles.findById(1L)).thenReturn(Optional.of(p));

    ProfileView view = service.delete(1L);

    verify(storage).delete("avatars/old.png");
    assertThat(view.avatar()).isNull();
  }

  @Test
  void keyOfExtractsKeyFromUrl() {
    assertThat(service.keyOf("http://minio:9000/devpath/avatars/a.png"))
        .isEqualTo("avatars/a.png");
    assertThat(service.keyOf("http://other/x")).isNull();
    assertThat(service.keyOf(null)).isNull();
  }
}
