package com.cvmatcher.cv_matcher_backend.administration.api;

import com.cvmatcher.cv_matcher_backend.identity.application.IdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "Administración", description = "Gestión de cuentas por administradores")
@SecurityRequirement(name = "bearerAuth")
public class AdminUsersController {
    private final IdentityService identity;

    public AdminUsersController(IdentityService identity) { this.identity = identity; }

    @GetMapping
    @Operation(summary = "Listar usuarios")
    public UsersPage list(@RequestParam(defaultValue = "0") @Min(0) int offset, @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return new UsersPage(identity.users(limit, offset), offset, limit);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Cambiar rol o estado de otra cuenta")
    public IdentityService.UserInfo update(@AuthenticationPrincipal UUID actor, @PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        if (request.role() == null && request.active() == null) throw new IllegalArgumentException();
        return identity.updateUser(actor, id, request.role(), request.active(), request.version());
    }

    record UsersPage(List<IdentityService.UserInfo> users, int offset, int limit) { }
    record UpdateUserRequest(@Schema(allowableValues = {"RECRUITER", "ADMIN"}) String role, Boolean active, @NotNull @Min(0) Long version) { }
}
