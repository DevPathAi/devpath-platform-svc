package ai.devpath.platform.release;

public class ReleaseControlException extends RuntimeException {
	public enum Kind { BAD_REQUEST, UNAUTHORIZED, NOT_FOUND }

	private final Kind kind;

	public ReleaseControlException(String message) {
		this(Kind.BAD_REQUEST, message);
	}

	public ReleaseControlException(Kind kind, String message) {
		super(message);
		this.kind = kind;
	}

	public Kind kind() { return kind; }
}
