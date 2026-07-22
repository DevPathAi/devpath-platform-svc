package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.devpath.platform.ads.dto.AdRow;
import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.storage.ObjectStorage;
import ai.devpath.shared.storage.ObjectStorage.StoredObject;
import ai.devpath.shared.storage.StoredFileValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AdImageServiceTest {

  private final AdvertisementRepository repo = mock(AdvertisementRepository.class);

  @Test
  void uploadWithoutStorageThrows503() {
    @SuppressWarnings("unchecked")
    ObjectProvider<ObjectStorage> storage = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<StoredFileValidator> validator = mock(ObjectProvider.class);
    when(storage.getIfAvailable()).thenReturn(null);
    when(validator.getIfAvailable()).thenReturn(null);

    Advertisement a = new Advertisement();
    a.setTitle("t"); a.setLinkUrl("https://e.com"); a.setSlot("DASHBOARD_TOP"); a.setStatus("ACTIVE"); a.setWeight(1);
    when(repo.findById(1L)).thenReturn(java.util.Optional.of(a));

    AdImageService svc = new AdImageService(repo, storage, validator);
    assertThatThrownBy(() -> svc.upload(1L, new byte[]{1}, "image/png", "x.png"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void uploadSuccessReturnsAdRowWithImageUrl() {
    // prepare storage mock
    ObjectStorage storageMock = mock(ObjectStorage.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<ObjectStorage> storageProvider = mock(ObjectProvider.class);
    when(storageProvider.getIfAvailable()).thenReturn(storageMock);

    // prepare validator mock — key() returns a deterministic key, validate() is void (no-op by default)
    StoredFileValidator validatorMock = mock(StoredFileValidator.class);
    when(validatorMock.key(eq("ads"), eq("x.png"))).thenReturn("ads/test-key.png");
    @SuppressWarnings("unchecked")
    ObjectProvider<StoredFileValidator> validatorProvider = mock(ObjectProvider.class);
    when(validatorProvider.getIfAvailable()).thenReturn(validatorMock);

    // storage.put returns a StoredObject with a known URL
    StoredObject stored = new StoredObject("ads/test-key.png", "https://cdn/x.png");
    when(storageMock.put(eq("ads/test-key.png"), any(byte[].class), eq("image/png"))).thenReturn(stored);

    // repo returns a saved ad with the imageUrl set
    Advertisement ad = new Advertisement();
    ad.setTitle("배너"); ad.setLinkUrl("https://e.com");
    ad.setSlot("DASHBOARD_TOP"); ad.setStatus("ACTIVE"); ad.setWeight(1);
    when(repo.findById(1L)).thenReturn(java.util.Optional.of(ad));
    when(repo.save(ad)).thenReturn(ad);

    AdImageService svc = new AdImageService(repo, storageProvider, validatorProvider);
    AdRow result = svc.upload(1L, new byte[]{1, 2, 3}, "image/png", "x.png");

    assertThat(result.imageUrl()).isEqualTo("https://cdn/x.png");
  }
}
