package com.cvmatcher.cv_matcher_backend.identity.api;

import com.cvmatcher.cv_matcher_backend.identity.application.IdentityService;
import com.cvmatcher.cv_matcher_backend.identity.SecurityProperties;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final IdentityService service;
    private final boolean secureCookies;

    public AuthController(IdentityService service, SecurityProperties properties) {
        this.service = service;
        this.secureCookies = properties.secureCookies();
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void register(@Valid @RequestBody RegisterRequest request) {
        service.register(request.fullName(), request.email(), request.password());
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        var login = service.login(request.email(), request.password());
        refreshCookie(response, login.refreshToken(), Duration.ofHours(8));
        csrfCookie(response, Duration.ofHours(8));
        return new TokenResponse(login.accessToken(), "Bearer", login.forcePasswordChange());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@CookieValue("cv_refresh") String refresh, HttpServletResponse response) {
        var login = service.refresh(refresh);
        refreshCookie(response, login.refreshToken(), Duration.ofHours(8));
        csrfCookie(response, Duration.ofHours(8));
        return new TokenResponse(login.accessToken(), "Bearer", login.forcePasswordChange());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(value = "cv_refresh", required = false) String refresh, HttpServletResponse response) {
        if (refresh != null) service.logout(refresh);
        refreshCookie(response, "", Duration.ZERO);
        csrfCookie(response, Duration.ZERO);
    }

    @GetMapping("/me")
    public IdentityService.UserInfo me(@org.springframework.security.core.annotation.AuthenticationPrincipal UUID userId) {
        return service.me(userId);
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resetRequest(@Valid @RequestBody EmailRequest request) {
        service.requestToken(request.email(), "PASSWORD_RESET", null);
    }

    @PostMapping("/email-verification/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resend(@Valid @RequestBody EmailRequest request) {
        service.requestToken(request.email(), "EMAIL_VERIFICATION", null);
    }

    @PostMapping("/email-verification/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(@Valid @RequestBody TokenRequest request) {
        service.confirm(request.token(), "EMAIL_VERIFICATION", null);
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetConfirm(@Valid @RequestBody PasswordTokenRequest request) {
        service.confirm(request.token(), "PASSWORD_RESET", request.password());
    }

    @PostMapping("/password/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void passwordChange(@org.springframework.security.core.annotation.AuthenticationPrincipal UUID userId, @Valid @RequestBody PasswordChangeRequest request) {
        service.changePassword(userId, request.currentPassword(), request.newPassword());
    }

    @PostMapping("/email-change/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void emailChangeRequest(@org.springframework.security.core.annotation.AuthenticationPrincipal UUID userId, @Valid @RequestBody EmailChangeRequest request) {
        service.requestEmailChange(userId, request.currentPassword(), request.email());
    }

    @PostMapping("/email-change/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void emailChangeConfirm(@Valid @RequestBody TokenRequest request) {
        service.confirm(request.token(), "EMAIL_CHANGE", null);
    }

    private void refreshCookie(HttpServletResponse response, String value, Duration maxAge) {
        cookie(response, "cv_refresh", value, true, maxAge);
    }

    private void csrfCookie(HttpServletResponse response, Duration maxAge) {
        cookie(response, "XSRF-TOKEN", maxAge.isZero() ? "" : randomToken(), false, maxAge);
    }

    private void cookie(HttpServletResponse response, String name, String value, boolean httpOnly, Duration maxAge) {
        var cookie = ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(secureCookies)
                .sameSite("Lax")
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String randomToken() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    record RegisterRequest(@NotBlank String fullName, @Email @NotBlank String email, @NotBlank String password) {
    }

    record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
    }

    record EmailRequest(@Email @NotBlank String email) {
    }

    record TokenRequest(@NotBlank String token) {
    }

    record PasswordTokenRequest(@NotBlank String token, @NotBlank String password) {
    }

    record PasswordChangeRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    record EmailChangeRequest(@Email @NotBlank String email, @NotBlank String currentPassword) {
    }

    record TokenResponse(String accessToken, String tokenType, boolean forcePasswordChange) {
    }
}
