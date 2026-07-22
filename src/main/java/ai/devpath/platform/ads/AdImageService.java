package ai.devpath.platform.ads;

import ai.devpath.platform.ads.dto.AdRow;
import ai.devpath.shared.storage.ObjectStorage;
import ai.devpath.shared.storage.ObjectStorage.StoredObject;
import ai.devpath.shared.storage.StorageException;
import ai.devpath.shared.storage.StoredFileValidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 광고 소재 이미지 업로드. 스토리지 미구성 시 503(StorageException). */
@Service
public class AdImageService {

  private final AdvertisementRepository ads;
  private final ObjectProvider<ObjectStorage> storageProvider;
  private final ObjectProvider<StoredFileValidator> validatorProvider;

  public AdImageService(AdvertisementRepository ads,
      ObjectProvider<ObjectStorage> storageProvider,
      ObjectProvider<StoredFileValidator> validatorProvider) {
    this.ads = ads;
    this.storageProvider = storageProvider;
    this.validatorProvider = validatorProvider;
  }

  @Transactional
  public AdRow upload(long id, byte[] content, String contentType, String filename) {
    Advertisement a = ads.findById(id).orElseThrow(() -> new AdNotFoundException(id));
    ObjectStorage storage = storage();
    StoredFileValidator v = validator();
    v.validate(contentType, content.length);
    String key = v.key("ads", filename);
    StoredObject stored = storage.put(key, content, contentType);
    a.setImageUrl(stored.url());
    return AdRow.of(ads.save(a));
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
}
