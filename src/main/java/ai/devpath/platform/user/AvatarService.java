package ai.devpath.platform.user;

import ai.devpath.platform.user.dto.ProfileView;
import ai.devpath.shared.storage.ObjectStorage;
import ai.devpath.shared.storage.ObjectStorage.StoredObject;
import ai.devpath.shared.storage.StorageException;
import ai.devpath.shared.storage.StorageProperties;
import ai.devpath.shared.storage.StoredFileValidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 프로필 아바타 업로드/삭제. 스토리지 미구성 시 503(STORAGE_UNAVAILABLE). */
@Service
public class AvatarService {

  private final ObjectProvider<ObjectStorage> storageProvider;
  private final ObjectProvider<StoredFileValidator> validatorProvider;
  private final ObjectProvider<StorageProperties> propsProvider;
  private final UserProfileRepository profiles;

  public AvatarService(
      ObjectProvider<ObjectStorage> storageProvider,
      ObjectProvider<StoredFileValidator> validatorProvider,
      ObjectProvider<StorageProperties> propsProvider,
      UserProfileRepository profiles) {
    this.storageProvider = storageProvider;
    this.validatorProvider = validatorProvider;
    this.propsProvider = propsProvider;
    this.profiles = profiles;
  }

  @Transactional
  public ProfileView upload(long userId, byte[] content, String contentType, String filename) {
    ObjectStorage storage = storage();
    validator().validate(contentType, content.length);
    String key = validator().key("avatars", filename);
    StoredObject stored = storage.put(key, content, contentType);
    UserProfile p = profiles.findById(userId).orElseGet(() -> newProfile(userId));
    deleteQuietly(storage, keyOf(p.getAvatar())); // 기존 교체 best-effort
    p.setAvatar(stored.url());
    return ProfileView.of(profiles.save(p));
  }

  @Transactional
  public ProfileView delete(long userId) {
    ObjectStorage storage = storage();
    UserProfile p = profiles.findById(userId).orElseGet(() -> newProfile(userId));
    String key = keyOf(p.getAvatar());
    if (key != null) {
      storage.delete(key); // 삭제 장애 → StorageException(503), avatar 유지
    }
    p.setAvatar(null);
    return ProfileView.of(profiles.save(p));
  }

  String keyOf(String url) {
    if (url == null) {
      return null;
    }
    StorageProperties props = propsProvider.getIfAvailable();
    if (props == null) {
      return null;
    }
    String prefix = props.getPublicBaseUrl() + "/" + props.getBucket() + "/";
    return url.startsWith(prefix) ? url.substring(prefix.length()) : null;
  }

  private ObjectStorage storage() {
    ObjectStorage s = storageProvider.getIfAvailable();
    if (s == null) {
      throw new StorageException("스토리지가 구성되지 않았습니다");
    }
    return s;
  }

  private StoredFileValidator validator() {
    StoredFileValidator v = validatorProvider.getIfAvailable();
    if (v == null) {
      throw new StorageException("스토리지가 구성되지 않았습니다");
    }
    return v;
  }

  private void deleteQuietly(ObjectStorage storage, String key) {
    if (key == null) {
      return;
    }
    try {
      storage.delete(key);
    } catch (RuntimeException ignored) {
      // best-effort: 이전 객체 정리 실패는 무시(고아 객체는 후속 정리)
    }
  }

  private UserProfile newProfile(long userId) {
    UserProfile np = new UserProfile();
    np.setUserId(userId);
    return np;
  }
}
