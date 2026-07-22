package ai.devpath.platform.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.storage.ObjectStorage;
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
}
