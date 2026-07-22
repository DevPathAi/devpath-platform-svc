package ai.devpath.platform.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.platform.auth.refresh.RefreshTokenStore;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountServiceTest {

  private UserRepository users;
  private RefreshTokenStore refreshStore;
  private AccountService service;

  @BeforeEach
  void setup() {
    users = mock(UserRepository.class);
    refreshStore = mock(RefreshTokenStore.class);
    service = new AccountService(users, refreshStore);
  }

  @Test
  void softDeleteSetsDeletedAtSavesAndRevokesTokens() {
    User user = new User();
    when(users.findById(1L)).thenReturn(Optional.of(user));

    Instant before = Instant.now();
    service.softDelete(1L);
    Instant after = Instant.now();

    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(users).save(captor.capture());
    User saved = captor.getValue();
    assertThat(saved.getDeletedAt()).isNotNull();
    assertThat(saved.getDeletedAt()).isBetween(before, after);
    verify(refreshStore).revokeAll(1L);
  }

  @Test
  void softDeleteThrowsWhenUserMissing() {
    when(users.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.softDelete(99L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("user not found");
    verify(users, never()).save(org.mockito.ArgumentMatchers.any());
    verify(refreshStore, never()).revokeAll(org.mockito.ArgumentMatchers.anyLong());
  }
}
