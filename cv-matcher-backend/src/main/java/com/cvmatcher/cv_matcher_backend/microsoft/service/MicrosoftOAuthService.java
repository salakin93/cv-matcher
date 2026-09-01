package com.cvmatcher.cv_matcher_backend.microsoft.service;

import com.cvmatcher.cv_matcher_backend.microsoft.domain.MicrosoftOAuthAuthorizationAttempt;
import com.cvmatcher.cv_matcher_backend.microsoft.domain.MicrosoftOAuthConnection;
import com.cvmatcher.cv_matcher_backend.microsoft.repository.MicrosoftOAuthAuthorizationAttemptRepository;
import com.cvmatcher.cv_matcher_backend.microsoft.repository.MicrosoftOAuthConnectionRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MicrosoftOAuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final MicrosoftOAuthAuthorizationAttemptRepository attemptRepository;
    private final MicrosoftOAuthConnectionRepository connectionRepository;
    private final MicrosoftTokenClient tokenClient;
    private final AesGcmCipher cipher;
    private final String clientId;
    private final String redirectUri;

    public MicrosoftOAuthService(
            MicrosoftOAuthAuthorizationAttemptRepository attemptRepository,
            MicrosoftOAuthConnectionRepository connectionRepository,
            MicrosoftTokenClient tokenClient,
            AesGcmCipher cipher,
            @Value("${MICROSOFT_CLIENT_ID:}") String clientId,
            @Value("${MICROSOFT_REDIRECT_URI:http://localhost:8080/oauth2/callback/microsoft}") String redirectUri) {
        this.attemptRepository = attemptRepository;
        this.connectionRepository = connectionRepository;
        this.tokenClient = tokenClient;
        this.cipher = cipher;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
    }

    @Transactional
    public URI beginAuthorization() {
        if (clientId.isBlank()) {
            throw new IllegalStateException("Microsoft OAuth is not configured");
        }
        String state = randomUrlSafeValue();
        String verifier = randomUrlSafeValue();
        AesGcmCipher.EncryptedValue encryptedVerifier = cipher.encrypt(verifier);
        attemptRepository.save(new MicrosoftOAuthAuthorizationAttempt(
                sha256(state), encryptedVerifier.ciphertext(), encryptedVerifier.nonce(), Instant.now().plus(Duration.ofMinutes(10))));
        String challenge = base64Url(sha256Bytes(verifier));
        return URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"
                + "?client_id=" + encode(clientId)
                + "&response_type=code"
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_mode=query"
                + "&scope=" + encode("User.Read Mail.Read offline_access")
                + "&state=" + encode(state)
                + "&code_challenge=" + encode(challenge)
                + "&code_challenge_method=S256");
    }

    @Transactional
    public void completeAuthorization(String code, String state) {
        MicrosoftOAuthAuthorizationAttempt attempt = attemptRepository.findByStateHash(sha256(state))
                .filter(current -> current.isUsable(Instant.now()))
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired Microsoft authorization state"));
        attempt.consume();
        String verifier = cipher.decrypt(attempt.getCodeVerifierCiphertext(), attempt.getCodeVerifierNonce());
        MicrosoftTokenResponse tokens = tokenClient.exchangeAuthorizationCode(code, verifier);
        AesGcmCipher.EncryptedValue encryptedRefreshToken = cipher.encrypt(tokens.refreshToken());
        connectionRepository.findAllByActiveTrue().forEach(MicrosoftOAuthConnection::revoke);
        connectionRepository.flush();
        connectionRepository.save(new MicrosoftOAuthConnection(
                encryptedRefreshToken.ciphertext(), encryptedRefreshToken.nonce(), "v1"));
    }

    @Transactional(readOnly = true)
    public MicrosoftConnectionStatus connectionStatus() {
        return connectionRepository.findByActiveTrue()
                .map(connection -> new MicrosoftConnectionStatus(true, connection.getConnectedAt()))
                .orElseGet(() -> new MicrosoftConnectionStatus(false, null));
    }

    public boolean hasActiveConnection() {
        return connectionRepository.findByActiveTrue().isPresent();
    }

    @Transactional
    public String accessToken() {
        MicrosoftOAuthConnection connection = connectionRepository.findByActiveTrue()
                .orElseThrow(() -> new IllegalStateException("Microsoft authorization is required"));
        try {
            String refreshToken = cipher.decrypt(connection.getRefreshTokenCiphertext(), connection.getRefreshTokenNonce());
            return tokenClient.refreshAccessToken(refreshToken).accessToken();
        } catch (RuntimeException exception) {
            connection.revoke();
            throw new IllegalStateException("Microsoft reauthorization is required", exception);
        }
    }

    private String randomUrlSafeValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return base64Url(bytes);
    }

    private String sha256(String value) { return java.util.HexFormat.of().formatHex(sha256Bytes(value)); }

    private byte[] sha256Bytes(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private String base64Url(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private String encode(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8); }

    public record MicrosoftConnectionStatus(boolean connected, Instant connectedAt) {}
}
