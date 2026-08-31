package com.cvmatcher.cv_matcher_backend.microsoft.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "microsoft_oauth_authorization_attempt")
public class MicrosoftOAuthAuthorizationAttempt {

    @Id
    private UUID id;

    @Column(name = "state_hash", nullable = false, unique = true, length = 64)
    private String stateHash;

    @Column(name = "code_verifier_ciphertext", nullable = false)
    private byte[] codeVerifierCiphertext;

    @Column(name = "code_verifier_nonce", nullable = false)
    private byte[] codeVerifierNonce;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MicrosoftOAuthAuthorizationAttempt() {}

    public MicrosoftOAuthAuthorizationAttempt(
            String stateHash, byte[] codeVerifierCiphertext, byte[] codeVerifierNonce, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.stateHash = stateHash;
        this.codeVerifierCiphertext = codeVerifierCiphertext;
        this.codeVerifierNonce = codeVerifierNonce;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public boolean isUsable(Instant now) { return consumedAt == null && expiresAt.isAfter(now); }
    public void consume() { this.consumedAt = Instant.now(); }
    public byte[] getCodeVerifierCiphertext() { return codeVerifierCiphertext; }
    public byte[] getCodeVerifierNonce() { return codeVerifierNonce; }
}
