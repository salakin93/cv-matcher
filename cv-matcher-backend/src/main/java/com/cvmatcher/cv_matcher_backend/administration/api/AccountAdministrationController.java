package com.cvmatcher.cv_matcher_backend.administration.api;

import com.cvmatcher.cv_matcher_backend.administration.application.AccountAdministrationService;
import com.cvmatcher.cv_matcher_backend.identity.api.ApiError;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/admin/users")
@Tag(name = "Account administration", description = "Gestión administrativa de cuentas verificadas")
@SecurityRequirement(name = "bearerAuth")
public class AccountAdministrationController {
    private final AccountAdministrationService service;

    public AccountAdministrationController(AccountAdministrationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Listar cuentas", description = "Requiere un ADMIN con sesión vigente.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página de cuentas", content = @Content(schema = @Schema(implementation = AccountAdministrationService.AccountPage.class))),
            @ApiResponse(responseCode = "401", description = "Bearer inválido o sesión revocada", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol insuficiente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Filtros o paginación inválidos", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public AccountAdministrationService.AccountPage list(
            @Parameter(in = ParameterIn.QUERY) @RequestParam(required = false) AccountAdministrationService.Role role,
            @Parameter(in = ParameterIn.QUERY) @RequestParam(required = false) AccountAdministrationService.Status status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return service.list(role, status, page, size);
    }

    @PatchMapping("/{userId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cambiar rol de cuenta")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Rol aplicado o ya persistido"),
            @ApiResponse(responseCode = "401", description = "Bearer inválido o sesión revocada", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol insuficiente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Cuenta inexistente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Conflicto de administración", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Solicitud inválida", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void changeRole(
            @org.springframework.security.core.annotation.AuthenticationPrincipal UUID actorId,
            @PathVariable UUID userId,
            @Valid @RequestBody RoleRequest request
    ) {
        service.changeRole(actorId, userId, request.getRole());
    }

    @PatchMapping("/{userId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Activar o desactivar cuenta")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Estado aplicado o ya persistido"),
            @ApiResponse(responseCode = "401", description = "Bearer inválido o sesión revocada", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol insuficiente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Cuenta inexistente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Conflicto de administración", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Solicitud inválida", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void changeStatus(
            @org.springframework.security.core.annotation.AuthenticationPrincipal UUID actorId,
            @PathVariable UUID userId,
            @Valid @RequestBody StatusRequest request
    ) {
        service.changeStatus(actorId, userId, request.getStatus());
    }

    static final class RoleRequest {
        private AccountAdministrationService.Role role;
        private boolean unknownField;

        @NotNull
        @Schema(example = "ADMIN")
        public AccountAdministrationService.Role getRole() {
            return role;
        }

        @JsonSetter("role")
        public void setRole(AccountAdministrationService.Role role) {
            this.role = role;
        }

        @JsonAnySetter
        public void unknownField(String name, Object value) {
            unknownField = true;
        }

        @AssertFalse(message = "Los campos adicionales no están permitidos.")
        @JsonIgnore
        public boolean isUnknownField() {
            return unknownField;
        }
    }

    static final class StatusRequest {
        private AccountAdministrationService.Status status;
        private boolean unknownField;

        @NotNull
        @Schema(example = "DISABLED")
        public AccountAdministrationService.Status getStatus() {
            return status;
        }

        @JsonSetter("status")
        public void setStatus(AccountAdministrationService.Status status) {
            this.status = status;
        }

        @JsonAnySetter
        public void unknownField(String name, Object value) {
            unknownField = true;
        }

        @AssertFalse(message = "Los campos adicionales no están permitidos.")
        @JsonIgnore
        public boolean isUnknownField() {
            return unknownField;
        }

        @jakarta.validation.constraints.AssertTrue(message = "El estado debe ser ACTIVE o DISABLED.")
        @JsonIgnore
        public boolean isMutableStatus() {
            return status == null || status == AccountAdministrationService.Status.ACTIVE || status == AccountAdministrationService.Status.DISABLED;
        }
    }
}
