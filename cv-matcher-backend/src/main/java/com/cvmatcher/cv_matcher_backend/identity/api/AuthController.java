package com.cvmatcher.cv_matcher_backend.identity.api;

import com.cvmatcher.cv_matcher_backend.identity.SecurityProperties;
import com.cvmatcher.cv_matcher_backend.identity.application.IdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Identity", description = "Registro, autenticación y seguridad de cuentas")
public class AuthController {
    private final IdentityService service;
    private final boolean secureCookies;

    public AuthController(IdentityService service, SecurityProperties properties) {
        this.service = service;
        this.secureCookies = properties.secureCookies();
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Registrar reclutador", description = "Crea una cuenta pendiente de verificación. La respuesta es neutral para no enumerar cuentas.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Solicitud aceptada"),
            @ApiResponse(responseCode = "400", description = "Política de contraseña incumplida", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Datos de registro inválidos", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void register(@Valid @RequestBody RegisterRequest request) {
        service.register(request.fullName(), request.email(), request.password());
    }

    @PostMapping("/login")
    @Operation(summary = "Iniciar sesión", description = "Devuelve un access token y establece cookies cv_refresh y XSRF-TOKEN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sesión creada", headers = @Header(name = "Set-Cookie", description = "Cookies cv_refresh y XSRF-TOKEN", schema = @Schema(type = "string")), content = @Content(schema = @Schema(implementation = TokenResponse.class))),
            @ApiResponse(responseCode = "422", description = "Datos de login inválidos", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Credenciales inválidas", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        var login = service.login(request.email(), request.password());
        refreshCookie(response, login.refreshToken(), Duration.ofHours(8));
        csrfCookie(response, Duration.ofHours(8));
        return new TokenResponse(login.accessToken(), "Bearer", login.forcePasswordChange());
    }

    @PostMapping("/refresh")
    @Operation(summary = "Renovar access token", description = "Requiere la cookie cv_refresh y el valor de XSRF-TOKEN en el header X-CSRF-TOKEN. Rota el refresh token.")
    @Parameter(name = "cv_refresh", in = ParameterIn.COOKIE, required = true, description = "Cookie HttpOnly de sesión")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "Valor de la cookie XSRF-TOKEN")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token renovado", headers = @Header(name = "Set-Cookie", description = "Cookies refresh y CSRF rotadas", schema = @Schema(type = "string")), content = @Content(schema = @Schema(implementation = TokenResponse.class))),
            @ApiResponse(responseCode = "401", description = "Sesión inválida", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "CSRF inválido o ausente", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TokenResponse refresh(@CookieValue("cv_refresh") String refresh, HttpServletResponse response) {
        var login = service.refresh(refresh);
        refreshCookie(response, login.refreshToken(), Duration.ofHours(8));
        csrfCookie(response, Duration.ofHours(8));
        return new TokenResponse(login.accessToken(), "Bearer", login.forcePasswordChange());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cerrar sesión", description = "Requiere la cookie cv_refresh y el header X-CSRF-TOKEN. Expira ambas cookies.")
    @Parameter(name = "cv_refresh", in = ParameterIn.COOKIE, required = true, description = "Cookie HttpOnly de sesión")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "Valor de la cookie XSRF-TOKEN")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Sesión cerrada"),
            @ApiResponse(responseCode = "403", description = "CSRF inválido o ausente", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void logout(@CookieValue(value = "cv_refresh", required = false) String refresh, HttpServletResponse response) {
        if (refresh != null) service.logout(refresh);
        refreshCookie(response, "", Duration.ZERO);
        csrfCookie(response, Duration.ZERO);
    }

    @GetMapping("/me")
    @Operation(summary = "Consultar identidad actual")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Identidad segura"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT inválido o ausente", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public IdentityService.UserInfo me(@org.springframework.security.core.annotation.AuthenticationPrincipal UUID userId) {
        return service.me(userId);
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Solicitar restablecimiento de contraseña", description = "La respuesta es neutral aunque la cuenta no exista o no esté activa.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Solicitud aceptada"),
            @ApiResponse(responseCode = "422", description = "Correo inválido", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void resetRequest(@Valid @RequestBody EmailRequest request) {
        service.requestToken(request.email(), "PASSWORD_RESET", null);
    }

    @PostMapping("/email-verification/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Reenviar verificación", description = "Máximo tres reenvíos por cuenta y hora. La respuesta es neutral.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Solicitud aceptada"),
            @ApiResponse(responseCode = "422", description = "Correo inválido", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void resend(@Valid @RequestBody EmailRequest request) {
        service.requestToken(request.email(), "EMAIL_VERIFICATION", null);
    }

    @PostMapping("/email-verification/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Confirmar correo")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cuenta verificada"),
            @ApiResponse(responseCode = "400", description = "Token inválido, vencido o usado", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Token ausente", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void verify(@Valid @RequestBody TokenRequest request) {
        service.confirm(request.token(), "EMAIL_VERIFICATION", null);
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Confirmar restablecimiento de contraseña")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Contraseña actualizada"),
            @ApiResponse(responseCode = "400", description = "Token o política de contraseña inválidos", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Datos ausentes", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void resetConfirm(@Valid @RequestBody PasswordTokenRequest request) {
        service.confirm(request.token(), "PASSWORD_RESET", request.password());
    }

    @PostMapping("/password/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cambiar contraseña")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Contraseña actualizada y sesiones revocadas"),
            @ApiResponse(responseCode = "400", description = "Contraseña nueva inválida", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Bearer JWT inválido o contraseña actual incorrecta", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Datos ausentes", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void passwordChange(@org.springframework.security.core.annotation.AuthenticationPrincipal UUID userId, @Valid @RequestBody PasswordChangeRequest request) {
        service.changePassword(userId, request.currentPassword(), request.newPassword());
    }

    @PostMapping("/email-change/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Solicitar cambio de correo", description = "Requiere bearer JWT y contraseña actual.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Solicitud aceptada"),
            @ApiResponse(responseCode = "422", description = "Datos inválidos", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Bearer JWT o contraseña actual inválidos", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Debe cambiar contraseña", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void emailChangeRequest(@org.springframework.security.core.annotation.AuthenticationPrincipal UUID userId, @Valid @RequestBody EmailChangeRequest request) {
        service.requestEmailChange(userId, request.currentPassword(), request.email());
    }

    @PostMapping("/email-change/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Confirmar cambio de correo")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Correo actualizado y sesiones revocadas"),
            @ApiResponse(responseCode = "400", description = "Token inválido, vencido o usado", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Correo ya utilizado", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Token ausente", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
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

    record RegisterRequest(
            @Schema(example = "Ana Reclutadora") @NotBlank String fullName,
            @Schema(example = "ana@example.test") @Email @NotBlank String email,
            @Schema(example = "ClaveSegura1") @NotBlank String password
    ) {
    }

    record LoginRequest(
            @Schema(example = "ana@example.test") @Email @NotBlank String email,
            @Schema(example = "ClaveSegura1") @NotBlank String password
    ) {
    }

    record EmailRequest(@Schema(example = "ana@example.test") @Email @NotBlank String email) {
    }

    record TokenRequest(@Schema(example = "token-recibido-por-correo") @NotBlank String token) {
    }

    record PasswordTokenRequest(
            @Schema(example = "token-recibido-por-correo") @NotBlank String token,
            @Schema(example = "ClaveSegura1") @NotBlank String password
    ) {
    }

    record PasswordChangeRequest(
            @Schema(example = "ClaveActual1") @NotBlank String currentPassword,
            @Schema(example = "ClaveNueva2") @NotBlank String newPassword
    ) {
    }

    record EmailChangeRequest(
            @Schema(example = "nuevo@example.test") @Email @NotBlank String email,
            @Schema(example = "ClaveActual1") @NotBlank String currentPassword
    ) {
    }

    record TokenResponse(
            @Schema(example = "eyJhbGciOiJIUzI1NiJ9...") String accessToken,
            @Schema(example = "Bearer") String tokenType,
            @Schema(example = "false") boolean forcePasswordChange
    ) {
    }
}
