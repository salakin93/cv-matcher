package com.cvmatcher.cv_matcher_backend.microsoft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cvmatcher.cv_matcher_backend.microsoft.domain.MicrosoftOAuthConnection;
import com.cvmatcher.cv_matcher_backend.microsoft.repository.MicrosoftOAuthAuthorizationAttemptRepository;
import com.cvmatcher.cv_matcher_backend.microsoft.repository.MicrosoftOAuthConnectionRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MicrosoftOAuthServiceTest {
    @Test
    void refreshesAccessTokenAndPersistsRotatedRefreshToken() {
        MicrosoftOAuthConnectionRepository connections = mock(MicrosoftOAuthConnectionRepository.class);
        MicrosoftTokenClient tokens = mock(MicrosoftTokenClient.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        MicrosoftOAuthConnection connection = new MicrosoftOAuthConnection(new byte[] {1}, new byte[] {2}, "v1");
        when(connections.findByActiveTrue()).thenReturn(Optional.of(connection));
        when(cipher.decrypt(connection.getRefreshTokenCiphertext(), connection.getRefreshTokenNonce())).thenReturn("old-refresh");
        when(tokens.refreshAccessToken("old-refresh")).thenReturn(new MicrosoftTokenResponse("access", "new-refresh"));
        var encrypted = new AesGcmCipher.EncryptedValue(new byte[] {3}, new byte[] {4});
        when(cipher.encrypt("new-refresh")).thenReturn(encrypted);

        String accessToken = service(connections, tokens, cipher).accessToken();

        assertThat(accessToken).isEqualTo("access");
        assertThat(connection.getRefreshTokenCiphertext()).containsExactly(3);
        assertThat(connection.getRefreshTokenNonce()).containsExactly(4);
    }

    @Test
    void preservesExistingRefreshTokenWhenMicrosoftDoesNotRotateIt() {
        MicrosoftOAuthConnectionRepository connections = mock(MicrosoftOAuthConnectionRepository.class);
        MicrosoftTokenClient tokens = mock(MicrosoftTokenClient.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        MicrosoftOAuthConnection connection = new MicrosoftOAuthConnection(new byte[] {1}, new byte[] {2}, "v1");
        when(connections.findByActiveTrue()).thenReturn(Optional.of(connection));
        when(cipher.decrypt(connection.getRefreshTokenCiphertext(), connection.getRefreshTokenNonce())).thenReturn("old-refresh");
        when(tokens.refreshAccessToken("old-refresh")).thenReturn(new MicrosoftTokenResponse("access", null));

        assertThat(service(connections, tokens, cipher).accessToken()).isEqualTo("access");
        assertThat(connection.getRefreshTokenCiphertext()).containsExactly(1);
    }

    @Test
    void revokesConnectionWhenRefreshRequiresReauthorization() {
        MicrosoftOAuthConnectionRepository connections = mock(MicrosoftOAuthConnectionRepository.class);
        MicrosoftTokenClient tokens = mock(MicrosoftTokenClient.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        MicrosoftOAuthConnection connection = new MicrosoftOAuthConnection(new byte[] {1}, new byte[] {2}, "v1");
        when(connections.findByActiveTrue()).thenReturn(Optional.of(connection));
        when(cipher.decrypt(connection.getRefreshTokenCiphertext(), connection.getRefreshTokenNonce())).thenReturn("old-refresh");
        when(tokens.refreshAccessToken("old-refresh")).thenThrow(new MicrosoftReauthorizationRequiredException("reauthorize"));

        assertThatThrownBy(() -> service(connections, tokens, cipher).accessToken())
                .isInstanceOf(MicrosoftReauthorizationRequiredException.class);
        assertThat(connection.isActive()).isFalse();
    }

    private MicrosoftOAuthService service(MicrosoftOAuthConnectionRepository connections, MicrosoftTokenClient tokens, AesGcmCipher cipher) {
        return new MicrosoftOAuthService(mock(MicrosoftOAuthAuthorizationAttemptRepository.class), connections, tokens, cipher, "client", "https://app.test/callback");
    }
}
