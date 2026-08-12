package ai.devpath.platform.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.platform.consent.dto.ConsentSubmitRequest;
import ai.devpath.platform.user.User;
import ai.devpath.platform.user.UserRepository;
import java.time.Year;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConsentServiceTest {

  private ConsentRepository consents;
  private UserRepository users;
  private ConsentService service;

  @BeforeEach
  void setup() {
    consents = mock(ConsentRepository.class);
    users = mock(UserRepository.class);
    service = new ConsentService(consents, users);
    when(consents.save(any(Consent.class))).thenAnswer(inv -> inv.getArgument(0));
    when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  private ConsentSubmitRequest requestWith(Integer birthYear, ConsentSubmitRequest.Item... items) {
    return new ConsentSubmitRequest(List.of(items), birthYear);
  }

  @Test
  void submitRejectsUnderFourteen() {
    int underAge = Year.now().getValue() - 13; // 13세 → 미성년 차단
    ConsentSubmitRequest req =
        requestWith(underAge, new ConsentSubmitRequest.Item(ConsentType.TERMS, true));

    assertThatThrownBy(() -> service.submit(1L, req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("14세");
    verify(consents, never()).save(any(Consent.class));
    verify(users, never()).findById(any());
  }

  @Test
  void submitAllowsExactlyFourteen() {
    int exactly14 = Year.now().getValue() - 14;
    User user = new User();
    when(users.findById(1L)).thenReturn(Optional.of(user));
    when(consents.findByUserIdOrderByAgreedAtDesc(1L)).thenReturn(List.of());
    ConsentSubmitRequest req =
        requestWith(exactly14, new ConsentSubmitRequest.Item(ConsentType.TERMS, true));

    service.submit(1L, req);

    assertThat(user.getBirthYear()).isEqualTo(exactly14);
    verify(consents, times(1)).save(any(Consent.class));
    verify(users).save(user);
  }

  @Test
  void submitPersistsEachConsentAndSetsBirthYear() {
    int birthYear = Year.now().getValue() - 30;
    User user = new User();
    when(users.findById(1L)).thenReturn(Optional.of(user));
    when(consents.findByUserIdOrderByAgreedAtDesc(1L)).thenReturn(List.of());
    ConsentSubmitRequest req =
        requestWith(
            birthYear,
            new ConsentSubmitRequest.Item(ConsentType.TERMS, true),
            new ConsentSubmitRequest.Item(ConsentType.MARKETING, false));

    service.submit(1L, req);

    verify(consents, times(2)).save(any(Consent.class));
    assertThat(user.getBirthYear()).isEqualTo(birthYear);
    verify(users).save(user);
  }

  @Test
  void submitMarksConsentStatusDoneWhenAllRequiredAgreed() {
    User user = new User();
    when(users.findById(1L)).thenReturn(Optional.of(user));
    // latestPerType returns agreed TERMS + PRIVACY → all required satisfied
    Consent terms = consent(ConsentType.TERMS, true);
    Consent privacy = consent(ConsentType.PRIVACY, true);
    when(consents.findByUserIdOrderByAgreedAtDesc(1L)).thenReturn(List.of(terms, privacy));
    ConsentSubmitRequest req =
        requestWith(
            null,
            new ConsentSubmitRequest.Item(ConsentType.TERMS, true),
            new ConsentSubmitRequest.Item(ConsentType.PRIVACY, true));

    service.submit(1L, req);

    assertThat(user.getConsentStatus()).isEqualTo("DONE");
  }

  @Test
  void submitDoesNotMarkDoneWhenRequiredMissing() {
    User user = new User();
    when(users.findById(1L)).thenReturn(Optional.of(user));
    // only TERMS agreed, PRIVACY missing → not DONE
    when(consents.findByUserIdOrderByAgreedAtDesc(1L))
        .thenReturn(List.of(consent(ConsentType.TERMS, true)));
    ConsentSubmitRequest req =
        requestWith(null, new ConsentSubmitRequest.Item(ConsentType.TERMS, true));

    service.submit(1L, req);

    assertThat(user.getConsentStatus()).isNotEqualTo("DONE");
  }

  @Test
  void submitPersistsTermsAtVersionTwo() {
    User user = new User();
    when(users.findById(1L)).thenReturn(Optional.of(user));
    when(consents.findByUserIdOrderByAgreedAtDesc(1L)).thenReturn(List.of());
    ConsentSubmitRequest req =
        requestWith(1990, new ConsentSubmitRequest.Item(ConsentType.TERMS, true));

    service.submit(1L, req);

    ArgumentCaptor<Consent> saved = ArgumentCaptor.forClass(Consent.class);
    verify(consents).save(saved.capture());
    assertThat(saved.getValue().getType()).isEqualTo(ConsentType.TERMS);
    assertThat(saved.getValue().getVersion()).isEqualTo("v2");
  }

  // 약관만 개정했다. 처리방침 버전을 함께 올리면 동의 이력이 사실과 어긋난다
  // (재동의 시 PRIVACY 도 다시 받지만, 받는 것은 여전히 v1 이다).
  @Test
  void privacyStaysAtVersionOne() {
    assertThat(ConsentType.PRIVACY.version).isEqualTo("v1");
  }

  @Test
  void submitThrowsWhenUserMissing() {
    when(users.findById(1L)).thenReturn(Optional.empty());
    ConsentSubmitRequest req =
        requestWith(null, new ConsentSubmitRequest.Item(ConsentType.TERMS, true));

    assertThatThrownBy(() -> service.submit(1L, req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("user not found");
  }

  @Test
  void revokeRejectsRequiredTypeWithConflict() {
    assertThatThrownBy(() -> service.revoke(1L, ConsentType.TERMS))
        .isInstanceOf(ConsentRevokeConflictException.class);
    verify(consents, never()).save(any(Consent.class));
  }

  @Test
  void revokeThrowsWhenNoHistoryForOptionalType() {
    when(consents.findFirstByUserIdAndTypeOrderByAgreedAtDesc(1L, ConsentType.MARKETING))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.revoke(1L, ConsentType.MARKETING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("동의 이력이 없습니다");
  }

  @Test
  void revokeSetsAgreedFalseAndRevokedAtForOptionalType() {
    Consent latest = consent(ConsentType.MARKETING, true);
    when(consents.findFirstByUserIdAndTypeOrderByAgreedAtDesc(1L, ConsentType.MARKETING))
        .thenReturn(Optional.of(latest));

    service.revoke(1L, ConsentType.MARKETING);

    assertThat(latest.isAgreed()).isFalse();
    assertThat(latest.getRevokedAt()).isNotNull();
    verify(consents).save(latest);
  }

  @Test
  void statusOfReturnsUserStatusItemsAndBirthYear() {
    User user = new User();
    user.setBirthYear(1990);
    when(users.findById(1L)).thenReturn(Optional.of(user));
    when(consents.findByUserIdOrderByAgreedAtDesc(1L))
        .thenReturn(List.of(consent(ConsentType.TERMS, true)));

    var view = service.statusOf(1L);

    assertThat(view.consentStatus()).isEqualTo("PENDING");
    assertThat(view.birthYear()).isEqualTo(1990);
    assertThat(view.items()).hasSize(1);
    assertThat(view.items().get(0).type()).isEqualTo(ConsentType.TERMS);
  }

  private static Consent consent(ConsentType type, boolean agreed) {
    Consent c = new Consent();
    c.setType(type);
    c.setAgreed(agreed);
    c.setVersion(type.version);
    c.setAgreedAt(java.time.Instant.now());
    return c;
  }
}
