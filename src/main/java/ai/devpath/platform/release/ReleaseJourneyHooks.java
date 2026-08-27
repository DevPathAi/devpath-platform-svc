package ai.devpath.platform.release;

import ai.devpath.platform.user.User;
import java.util.Map;

public interface ReleaseJourneyHooks {
	ReleaseJourneyHooks NONE = new ReleaseJourneyHooks() {};

	default void prepare(ReleaseRunState run) {}

	default void login(ReleaseRunState run, User user) {}

	default void command(ReleaseRunState run, String command) {}

	default void command(
			ReleaseRunState run, String command, Map<String, Object> commandData) {
		command(run, command);
	}

	default boolean checkpoint(ReleaseRunState run, String checkpoint) {
		return false;
	}
}
