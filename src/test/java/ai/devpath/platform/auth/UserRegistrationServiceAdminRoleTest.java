package ai.devpath.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ai.devpath.platform.auth.UserRegistrationService.OauthUser;
import ai.devpath.platform.auth.crypto.TokenCipher;
import ai.devpath.platform.config.BetaProperties;
import ai.devpath.platform.outbox.OutboxRepository;
import ai.devpath.platform.user.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class UserRegistrationServiceAdminRoleTest {

    private UserRepository users;
    private UserOauthIdentityRepository identities;
    private UserProfileRepository profiles;
    private OutboxRepository outbox;
    private TokenCipher cipher;
    private JsonMapper jsonMapper;
    private BetaProperties betaProps;
    private UserRegistrationService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        identities = mock(UserOauthIdentityRepository.class);
        profiles = mock(UserProfileRepository.class);
        outbox = mock(OutboxRepository.class);
        cipher = mock(TokenCipher.class);
        jsonMapper = mock(JsonMapper.class);
        betaProps = mock(BetaProperties.class);

        when(betaProps.getAdminEmails()).thenReturn(List.of("admin@devpath.ai"));

        // identities.findBy... → empty (new user always)
        when(identities.findByProviderAndProviderUserId(any(), any()))
                .thenReturn(Optional.empty());
        // users.findByEmail → empty (always new)
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        // users.save returns the user passed in (simulate DB-assigned id)
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            // Simulate JPA-assigned id so writeOutbox doesn't NPE on getId()
            java.lang.reflect.Field idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(u, 1L);
            return u;
        });

        try {
            when(jsonMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        service = new UserRegistrationService(
                users, identities, profiles, outbox, cipher, jsonMapper, betaProps);
    }

    @Test
    void newUserWithAdminEmail_getsAdminRole() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);

        service.registerOrFind(
                new OauthUser("GOOGLE", "gid-admin", "Admin@Devpath.Ai", "AdminNick", null));

        verify(users).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo("ADMIN");
    }

    @Test
    void newUserWithNormalEmail_getsLearnerRole() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);

        service.registerOrFind(
                new OauthUser("GITHUB", "gid-normal", "normal@example.com", "NormalNick", null));

        verify(users).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo("LEARNER");
    }
}
