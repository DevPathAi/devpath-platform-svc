package ai.devpath.platform.ads;

import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.error.ErrorCode;

public class AdNotFoundException extends ApiException {
  public AdNotFoundException(long id) {
    super(ErrorCode.RESOURCE_NOT_FOUND, "광고 없음: " + id);
  }
}
