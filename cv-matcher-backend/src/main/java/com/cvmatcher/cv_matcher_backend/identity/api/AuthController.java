package com.cvmatcher.cv_matcher_backend.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final IdentityService service;

    public AuthController(IdentityService service) {
        this.service = service;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void register(@Valid @RequestBody RegisterRequest request) {
        service.register(request.fullName(), request.email(), request.password());
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        var login = service.login(request.email(), request.password());
        cookie(response, "cv_refresh", login.refreshToken(), true);
        return new TokenResponse(login.accessToken(), "Bearer", login.forcePasswordChange());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@CookieValue("cv_refresh") String refresh, HttpServletResponse response) {
        var login = service.refresh(refresh);
        cookie(response, "cv_refresh", login.refreshToken(), true);
        return new TokenResponse(login.accessToken(), "Bearer", login.forcePasswordChange());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(value = "cv_refresh", required = false) String refresh, HttpServletResponse response) {
        if (refresh != null) service.logout(refresh);
        cookie(response, "cv_refresh", "", true);
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

    private void cookie(HttpServletResponse response, String name, String value, boolean httpOnly) {
        var c = new Cookie(name, value);
        c.setHttpOnly(httpOnly);
        c.setSecure(true);
        c.setPath("/api/v1/auth");
        c.setMaxAge(8 * 60 * 60);
        response.addCookie(c);
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
