package ai.devpath.platform.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ReleaseSandboxHooksTest {
  private static final String CANDIDATE = "a".repeat(64);
  private static final String RUN_KEY = "R".repeat(43);

  @Test
  void armsFaultAndQueriesCheckpointWithInternalCredential() {
		ReleaseControlProperties properties = new ReleaseControlProperties();
		properties.setSandboxOrigin("http://devpath-sandbox:8080");
		properties.setInternalToken("release-internal-token");
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		UserRepository users = org.mockito.Mockito.mock(UserRepository.class);
		ReleaseSandboxHooks hooks = new ReleaseSandboxHooks(properties, users, builder.build());
		ReleaseRunState run = run();

		server.expect(once(), requestTo("http://devpath-sandbox:8080/internal/release/sandbox/"
				+ CANDIDATE + "/" + RUN_KEY + "/commands/next-run-timeout"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("X-DevPath-Internal-Token", "release-internal-token"))
			.andRespond(withSuccess("{\"accepted\":true}", MediaType.APPLICATION_JSON));
		server.expect(once(), requestTo("http://devpath-sandbox:8080/internal/release/sandbox/"
				+ CANDIDATE + "/" + RUN_KEY + "/checkpoints/owner-recovery-timed-out"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header("X-DevPath-Internal-Token", "release-internal-token"))
			.andRespond(withSuccess("{\"passed\":true}", MediaType.APPLICATION_JSON));
		hooks.command(run, "next-run-timeout");
		assertThat(hooks.checkpoint(run, "owner-recovery-timed-out")).isTrue();
		server.verify();
	}

	@Test
	void staleFixtureUsesOnlyTheDeterministicUsersOpaqueDatabaseId() {
		ReleaseControlProperties properties = new ReleaseControlProperties();
		properties.setSandboxOrigin("http://devpath-sandbox:8080");
		properties.setInternalToken("release-internal-token");
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		UserRepository users = org.mockito.Mockito.mock(UserRepository.class);
		User user = org.mockito.Mockito.mock(User.class);
		org.mockito.Mockito.when(user.getId()).thenReturn(42L);
		org.mockito.Mockito.when(users.findByEmail("release@example.test"))
			.thenReturn(Optional.of(user));
		ReleaseSandboxHooks hooks = new ReleaseSandboxHooks(properties, users, builder.build());

		server.expect(requestTo("http://devpath-sandbox:8080/internal/release/sandbox/"
				+ CANDIDATE + "/" + RUN_KEY + "/commands/seed-stale-running"))
			.andExpect(jsonPath("$.user_id").value(42))
			.andRespond(withSuccess(
				"{\"accepted\":true,\"session_id\":82}", MediaType.APPLICATION_JSON));
		hooks.command(run(), "seed-stale-running");
		server.verify();
	}

	@Test
	void aiFaultsAndPrivacyCheckpointsUseTheSameOpaqueOwnerAndWorkloadCredential() {
		ReleaseControlProperties properties = new ReleaseControlProperties();
		properties.setAiOrigin("http://devpath-ai:8080");
		properties.setInternalToken("release-internal-token");
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		UserRepository users = org.mockito.Mockito.mock(UserRepository.class);
		User user = org.mockito.Mockito.mock(User.class);
		org.mockito.Mockito.when(user.getId()).thenReturn(42L);
		org.mockito.Mockito.when(users.findByEmail("release@example.test"))
			.thenReturn(Optional.of(user));
		ReleaseSandboxHooks hooks = new ReleaseSandboxHooks(properties, users, builder.build());

		server.expect(requestTo("http://devpath-ai:8080/internal/release/ai/"
				+ CANDIDATE + "/" + RUN_KEY + "/commands/fail-next-review"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("X-DevPath-Internal-Token", "release-internal-token"))
			.andExpect(jsonPath("$.user_id").value(42))
			.andRespond(withSuccess("{\"accepted\":true}", MediaType.APPLICATION_JSON));
		server.expect(requestTo("http://devpath-ai:8080/internal/release/ai/"
				+ CANDIDATE + "/" + RUN_KEY
				+ "/checkpoints/kafka-outbox-review-correlated"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("{\"passed\":true}", MediaType.APPLICATION_JSON));

		hooks.command(run(), "fail-next-review");
		assertThat(hooks.checkpoint(run(), "kafka-outbox-review-correlated")).isTrue();
		server.verify();
	}

	@Test
	void mentorPreviewCheckpointIsOwnedByLcs() {
		ReleaseControlProperties properties = new ReleaseControlProperties();
		properties.setLcsOrigin("http://devpath-lcs:8080");
		properties.setInternalToken("release-internal-token");
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		ReleaseSandboxHooks hooks = new ReleaseSandboxHooks(
			properties, org.mockito.Mockito.mock(UserRepository.class), builder.build());

		server.expect(requestTo("http://devpath-lcs:8080/internal/release/lcs/"
				+ CANDIDATE + "/" + RUN_KEY
				+ "/checkpoints/private-mentor-prompt-preview"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header("X-DevPath-Internal-Token", "release-internal-token"))
			.andRespond(withSuccess("{\"passed\":true}", MediaType.APPLICATION_JSON));

		assertThat(hooks.checkpoint(run(), "private-mentor-prompt-preview")).isTrue();
		server.verify();
	}

	@Test
	void learningReplaysAndOwnedCheckpointsUseTheDeterministicUser() {
		ReleaseControlProperties properties = new ReleaseControlProperties();
		properties.setLearningOrigin("http://devpath-learning:8080");
		properties.setInternalToken("release-internal-token");
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		UserRepository users = org.mockito.Mockito.mock(UserRepository.class);
		User user = org.mockito.Mockito.mock(User.class);
		org.mockito.Mockito.when(user.getId()).thenReturn(42L);
		org.mockito.Mockito.when(users.findByEmail("release@example.test"))
			.thenReturn(Optional.of(user));
		ReleaseSandboxHooks hooks = new ReleaseSandboxHooks(properties, users, builder.build());

		server.expect(requestTo("http://devpath-learning:8080/internal/release/learning/"
				+ CANDIDATE + "/" + RUN_KEY + "/commands/replay-claim"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("X-DevPath-Internal-Token", "release-internal-token"))
			.andExpect(jsonPath("$.user_id").value(42))
			.andRespond(withSuccess("{\"accepted\":true}", MediaType.APPLICATION_JSON));
		server.expect(requestTo("http://devpath-learning:8080/internal/release/learning/"
				+ CANDIDATE + "/" + RUN_KEY
				+ "/checkpoints/claim-replay-one-owned-result?user_id=42"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header("X-DevPath-Internal-Token", "release-internal-token"))
			.andRespond(withSuccess("{\"passed\":true}", MediaType.APPLICATION_JSON));

		hooks.command(run(), "replay-claim");
		assertThat(hooks.checkpoint(run(), "claim-replay-one-owned-result")).isTrue();
		server.verify();
	}

	@Test
	void guestPreviewCheckpointDoesNotRequireAnOauthUser() {
		ReleaseControlProperties properties = new ReleaseControlProperties();
		properties.setLearningOrigin("http://devpath-learning:8080");
		properties.setInternalToken("release-internal-token");
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		UserRepository users = org.mockito.Mockito.mock(UserRepository.class);
		ReleaseSandboxHooks hooks = new ReleaseSandboxHooks(properties, users, builder.build());

		server.expect(requestTo("http://devpath-learning:8080/internal/release/learning/"
				+ CANDIDATE + "/" + RUN_KEY
				+ "/checkpoints/guest-preview-owned-by-guest"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("{\"passed\":true}", MediaType.APPLICATION_JSON));

		assertThat(hooks.checkpoint(run(), "guest-preview-owned-by-guest")).isTrue();
		org.mockito.Mockito.verifyNoInteractions(users);
		server.verify();
	}

	@Test
	void workspaceLoginPreparesLearningBeforeOpeningTheOnboardedUserGate() {
		ReleaseControlProperties properties = new ReleaseControlProperties();
		properties.setLearningOrigin("http://devpath-learning:8080");
		properties.setInternalToken("release-internal-token");
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		UserRepository users = org.mockito.Mockito.mock(UserRepository.class);
		User user = org.mockito.Mockito.mock(User.class);
		org.mockito.Mockito.when(user.getId()).thenReturn(42L);
		ReleaseSandboxHooks hooks = new ReleaseSandboxHooks(properties, users, builder.build());

		server.expect(requestTo("http://devpath-learning:8080/internal/release/learning/"
				+ CANDIDATE + "/" + RUN_KEY + "/prepare"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("X-DevPath-Internal-Token", "release-internal-token"))
			.andExpect(jsonPath("$.user_id").value(42))
			.andRespond(withSuccess("{\"accepted\":true}", MediaType.APPLICATION_JSON));

		hooks.login(run(), user);

		org.mockito.Mockito.verify(user).setConsentStatus("DONE");
		org.mockito.Mockito.verify(user).setOnboardingStatus("DONE");
		org.mockito.Mockito.verify(user).setBirthYear(2000);
		org.mockito.Mockito.verify(users).save(user);
		server.verify();
	}

	private static ReleaseRunState run() {
		return new ReleaseRunState(
			CANDIDATE,
			RUN_KEY,
			"mission-spine-workspace",
			"f".repeat(40),
			"release@example.test",
			false,
			List.of(),
			Set.of(),
			true,
			true);
	}
}
