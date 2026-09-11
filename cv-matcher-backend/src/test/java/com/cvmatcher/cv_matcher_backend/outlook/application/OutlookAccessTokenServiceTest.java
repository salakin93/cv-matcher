package com.cvmatcher.cv_matcher_backend.outlook.application;

import com.cvmatcher.cv_matcher_backend.outlook.OutlookProperties;
import com.cvmatcher.cv_matcher_backend.outlook.infrastructure.OutlookOAuthClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutlookAccessTokenServiceTest {
    @Test
    void repeatedRefreshRotationConflictsDoNotReturnOrCacheUnconfirmedTokens() {
        var connections = mock(OutlookConnectionStore.class);
        var oauthClient = mock(OutlookOAuthClient.class);
        var observability = mock(OutlookObservability.class);
        var firstConnection = new OutlookConnectionStore.Connection("CONNECTED", new byte[]{1}, 1, 1);
        var secondConnection = new OutlookConnectionStore.Connection("CONNECTED", new byte[]{2}, 1, 2);
        var currentConnection = new OutlookConnectionStore.Connection("CONNECTED", new byte[]{3}, 1, 3);
        var service = new OutlookAccessTokenService(properties(), connections, oauthClient, observability);

        when(connections.connection()).thenReturn(firstConnection, secondConnection, currentConnection);
        when(connections.decrypt(any())).thenReturn("refresh-token");
        when(connections.applyRefreshRotation(any(), any())).thenReturn(false, false, true);
        when(oauthClient.refreshAccessToken("refresh-token")).thenReturn(
                token("stale-access-token-1", "stale-refresh-token-1"),
                token("stale-access-token-2", "stale-refresh-token-2"),
                token("confirmed-access-token", "confirmed-refresh-token"));

        var failure = assertThrows(OutlookException.class, service::accessToken);

        assertEquals("OUTLOOK_TEMPORARILY_UNAVAILABLE", failure.code());
        verify(observability).tokenRefresh("transient_failure");
        assertEquals("confirmed-access-token", service.accessToken().value());
        verify(oauthClient, org.mockito.Mockito.times(3)).refreshAccessToken("refresh-token");
    }

    @Test
    void staleKeyVersionFailureReloadsTheConnectedCallbackCredential() {
        var connections = mock(OutlookConnectionStore.class);
        var oauthClient = mock(OutlookOAuthClient.class);
        var observability = mock(OutlookObservability.class);
        var stale = new OutlookConnectionStore.Connection("CONNECTED", new byte[]{1}, 2, 1);
        var replacement = new OutlookConnectionStore.Connection("CONNECTED", new byte[]{2}, 1, 2);
        var service = new OutlookAccessTokenService(properties(), connections, oauthClient, observability);

        when(connections.connection()).thenReturn(stale, replacement);
        when(connections.markDecryptionFailed(stale)).thenReturn(false);
        when(connections.decrypt(replacement)).thenReturn("callback-refresh-token");
        when(connections.applyRefreshRotation(eq(replacement.ciphertext()), any())).thenReturn(true);
        when(oauthClient.refreshAccessToken("callback-refresh-token")).thenReturn(token("callback-access-token", "rotated-refresh-token"));

        assertEquals("callback-access-token", service.accessToken().value());
        assertEquals("CONNECTED", replacement.status());
        verify(connections).markDecryptionFailed(stale);
        verify(observability).tokenRefresh("success");
    }

    @Test
    void staleAesGcmFailureReloadsTheConnectedCallbackCredential() {
        var connections = mock(OutlookConnectionStore.class);
        var oauthClient = mock(OutlookOAuthClient.class);
        var observability = mock(OutlookObservability.class);
        var stale = new OutlookConnectionStore.Connection("CONNECTED", new byte[]{1}, 1, 1);
        var replacement = new OutlookConnectionStore.Connection("CONNECTED", new byte[]{2}, 1, 2);
        var service = new OutlookAccessTokenService(properties(), connections, oauthClient, observability);

        when(connections.connection()).thenReturn(stale, replacement);
        when(connections.decrypt(stale)).thenThrow(new IllegalArgumentException("Ciphertext invalid"));
        when(connections.markDecryptionFailed(stale)).thenReturn(false);
        when(connections.decrypt(replacement)).thenReturn("callback-refresh-token");
        when(connections.applyRefreshRotation(eq(replacement.ciphertext()), any())).thenReturn(true);
        when(oauthClient.refreshAccessToken("callback-refresh-token")).thenReturn(token("callback-access-token", "rotated-refresh-token"));

        assertEquals("callback-access-token", service.accessToken().value());
        assertEquals("CONNECTED", replacement.status());
        verify(connections).markDecryptionFailed(stale);
        verify(observability).tokenRefresh("success");
    }

    private static OutlookProperties properties() {
        return new OutlookProperties(null, null, null, null, null, null, null, 1,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 3);
    }

    private static OutlookOAuthClient.TokenResponse token(String accessToken, String refreshToken) {
        return new OutlookOAuthClient.TokenResponse(accessToken, refreshToken, null, null, 3600);
    }
}
