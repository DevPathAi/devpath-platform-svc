package ai.devpath.platform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.platform.support.dto.PublicSupportCreateRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PublicSupportServiceTest {

  private TurnstileVerifier turnstile;
  private PublicSupportRateLimiter rateLimiter;
  private SupportService support;
  private PublicSupportService service;

  @BeforeEach
  void setUp() {
    turnstile = mock(TurnstileVerifier.class);
    rateLimiter = mock(PublicSupportRateLimiter.class);
    support = mock(SupportService.class);
    service = new PublicSupportService(
        turnstile,
        rateLimiter,
        support,
        Clock.fixed(Instant.parse("2026-09-05T06:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void verifiesTurnstileThenRateLimitThenStoresMaskedPublicRequest() {
    PublicSupportCreateRequest request = valid();
    SupportRequest saved = new SupportRequest();
    when(turnstile.verify("token-value", "203.0.113.10")).thenReturn(true);
    when(rateLimiter.allow("203.0.113.10", "person@example.com")).thenReturn(true);
    when(support.createPublic(any(), any(), any(), any(), any())).thenReturn(saved);

    assertThat(service.create(request, "203.0.113.10")).isSameAs(saved);

    var order = inOrder(turnstile, rateLimiter, support);
    order.verify(turnstile).verify("token-value", "203.0.113.10");
    order.verify(rateLimiter).allow("203.0.113.10", "person@example.com");
    order.verify(support).createPublic(
        "person@example.com", "INQUIRY", "문의", "person@example.com의 토큰 token=secret",
        Instant.parse("2026-09-05T06:00:00Z"));
  }

  @Test
  void failedTurnstileNeverConsumesRateLimitOrStores() {
    when(turnstile.verify(any(), any())).thenReturn(false);

    assertThatThrownBy(() -> service.create(valid(), "203.0.113.10"))
        .isInstanceOf(PublicSupportException.class)
        .extracting("code").isEqualTo("TURNSTILE_FAILED");
    verify(rateLimiter, never()).allow(any(), any());
    verify(support, never()).createPublic(any(), any(), any(), any(), any());
  }

  @Test
  void exhaustedRateLimitNeverStores() {
    when(turnstile.verify(any(), any())).thenReturn(true);
    when(rateLimiter.allow(any(), any())).thenReturn(false);

    assertThatThrownBy(() -> service.create(valid(), "203.0.113.10"))
        .isInstanceOf(PublicSupportException.class)
        .extracting("code").isEqualTo("RATE_LIMITED");
    verify(support, never()).createPublic(any(), any(), any(), any(), any());
  }

  @Test
  void rejectsMissingConsentAndInvalidEmailBeforeExternalChecks() {
    PublicSupportCreateRequest bad = new PublicSupportCreateRequest(
        "ERROR", "not-an-email", "제목", "본문", false, "token-value");
    assertThatThrownBy(() -> service.create(bad, "203.0.113.10"))
        .isInstanceOf(IllegalArgumentException.class);
    verify(turnstile, never()).verify(any(), any());
  }

  @Test
  void rejectsUnknownTypeBeforeExternalChecks() {
    PublicSupportCreateRequest bad = new PublicSupportCreateRequest(
        "ACCOUNT_DELETE", "person@example.com", "제목", "본문", true, "token-value");

    assertThatThrownBy(() -> service.create(bad, "203.0.113.10"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("type");
    verify(turnstile, never()).verify(any(), any());
    verify(rateLimiter, never()).allow(any(), any());
    verify(support, never()).createPublic(any(), any(), any(), any(), any());
  }

  @Test
  void neverCopiesRawIpOrTurnstileTokenIntoPersistenceArguments() {
    when(turnstile.verify(any(), any())).thenReturn(true);
    when(rateLimiter.allow(any(), any())).thenReturn(true);
    when(support.createPublic(any(), any(), any(), any(), any())).thenReturn(new SupportRequest());

    service.create(valid(), "203.0.113.10");

    ArgumentCaptor<String> strings = ArgumentCaptor.forClass(String.class);
    verify(support).createPublic(strings.capture(), strings.capture(), strings.capture(), strings.capture(), any());
    assertThat(strings.getAllValues())
        .noneMatch(value -> value.contains("203.0.113.10") || value.contains("token-value"));
  }

  private static PublicSupportCreateRequest valid() {
    return new PublicSupportCreateRequest(
        "INQUIRY",
        " Person@Example.com ",
        " 문의 ",
        "person@example.com의 토큰 token=secret",
        true,
        "token-value");
  }
}
