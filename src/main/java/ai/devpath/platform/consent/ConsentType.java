package ai.devpath.platform.consent;

/** 동의 항목 종류. 필수(TERMS·PRIVACY)는 철회 시 409(ConsentRevokeConflictException). */
public enum ConsentType {
	TERMS(true, "v2"),
	PRIVACY(true, "v1"),
	MARKETING(false, "v1"),
	LCS_ATTACH(false, "v1"),
	ERROR_LOG(false, "v1");

	public final boolean required;
	public final String version;

	ConsentType(boolean required, String version) {
		this.required = required;
		this.version = version;
	}
}
