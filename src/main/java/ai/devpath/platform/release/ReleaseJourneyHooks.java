package ai.devpath.platform.release;

public interface ReleaseJourneyHooks {
	ReleaseJourneyHooks NONE = new ReleaseJourneyHooks() {};

	default void prepare(ReleaseRunState run) {}

	default void command(ReleaseRunState run, String command) {}

	default boolean checkpoint(ReleaseRunState run, String checkpoint) {
		return false;
	}
}
