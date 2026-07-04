package ai.devpath.platform.consent;

import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.error.ErrorCode;

/** 필수 동의 철회 시도 → 스펙 §3.4 CONFLICT(409). 공용 ApiExceptionHandler가 envelope로 렌더. */
public class ConsentRevokeConflictException extends ApiException {
	public ConsentRevokeConflictException(String msg) {
		super(ErrorCode.CONFLICT, msg);
	}
}
