package com.cvmatcher.cv_matcher_backend.vacancy.api;

import com.cvmatcher.cv_matcher_backend.identity.api.ApiError;
import com.cvmatcher.cv_matcher_backend.vacancy.application.VacancyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vacancies")
@Tag(name = "Vacancies", description = "Gestión compartida de vacantes y requisitos")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
public class VacancyController {
    private final VacancyService service;

    public VacancyController(VacancyService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear vacante", description = "Crea una vacante activa. Los títulos activos duplicados devuelven la advertencia DUPLICATE_ACTIVE_TITLE.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Vacante creada", content = @Content(schema = @Schema(implementation = VacancyService.Vacancy.class))),
            @ApiResponse(responseCode = "401", description = "Bearer JWT inválido o ausente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol no permitido", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Datos inválidos", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public VacancyService.Vacancy create(@AuthenticationPrincipal UUID actorUserId, @Valid @RequestBody VacancyRequest request) {
        return service.create(actorUserId, request.command());
    }

    @GetMapping
    @Operation(summary = "Listar vacantes")
    @ApiResponse(responseCode = "200", description = "Vacantes compartidas")
    public List<VacancyService.Vacancy> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar vacante")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Vacante encontrada"),
            @ApiResponse(responseCode = "404", description = "Vacante inexistente", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public VacancyService.Vacancy get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Reemplazar vacante", description = "Reemplaza todos los datos y requisitos de una vacante activa usando su versión actual.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Vacante reemplazada"),
            @ApiResponse(responseCode = "404", description = "Vacante inexistente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Versión o estado incompatible", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Datos inválidos", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public VacancyService.Vacancy replace(@AuthenticationPrincipal UUID actorUserId, @PathVariable UUID id, @Valid @RequestBody ReplaceVacancyRequest request) {
        return service.replace(actorUserId, id, request.version(), request.command());
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "Archivar vacante")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Vacante archivada o ya archivada"),
            @ApiResponse(responseCode = "404", description = "Vacante inexistente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Versión incompatible", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Datos inválidos", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public VacancyService.Vacancy archive(@AuthenticationPrincipal UUID actorUserId, @PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return service.archive(actorUserId, id, request.version());
    }

    @PostMapping("/{id}/reactivate")
    @Operation(summary = "Reactivar vacante")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Vacante reactivada o ya activa"),
            @ApiResponse(responseCode = "404", description = "Vacante inexistente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Versión incompatible", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Datos inválidos", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public VacancyService.Vacancy reactivate(@AuthenticationPrincipal UUID actorUserId, @PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return service.reactivate(actorUserId, id, request.version());
    }

    public record VacancyRequest(
            @Schema(example = "Ingeniero de software") @NotBlank String title,
            @Schema(example = "Desarrolla servicios para el equipo de producto.") @NotBlank String description,
            @Schema(example = "2026-09-01") @NotNull LocalDate receptionStart,
            @Schema(example = "2026-09-30") @NotNull LocalDate receptionEnd,
            @NotNull @Size(min = 1, max = 30) List<@NotNull @Valid RequirementRequest> requirements
    ) {
        @AssertTrue(message = "La fecha inicial no puede ser posterior a la fecha final.")
        public boolean isReceptionRangeValid() {
            return receptionStart == null || receptionEnd == null || !receptionStart.isAfter(receptionEnd);
        }

        VacancyService.VacancyCommand command() {
            return new VacancyService.VacancyCommand(title, description, receptionStart, receptionEnd,
                    requirements.stream().map(RequirementRequest::command).toList());
        }
    }

    public record ReplaceVacancyRequest(
            @NotNull @Min(0) Long version,
            @Schema(example = "Ingeniero de software") @NotBlank String title,
            @Schema(example = "Desarrolla servicios para el equipo de producto.") @NotBlank String description,
            @Schema(example = "2026-09-01") @NotNull LocalDate receptionStart,
            @Schema(example = "2026-09-30") @NotNull LocalDate receptionEnd,
            @NotNull @Size(min = 1, max = 30) List<@NotNull @Valid RequirementRequest> requirements
    ) {
        @AssertTrue(message = "La fecha inicial no puede ser posterior a la fecha final.")
        public boolean isReceptionRangeValid() {
            return receptionStart == null || receptionEnd == null || !receptionStart.isAfter(receptionEnd);
        }

        VacancyService.VacancyCommand command() {
            return new VacancyService.VacancyCommand(title, description, receptionStart, receptionEnd,
                    requirements.stream().map(RequirementRequest::command).toList());
        }
    }

    public record RequirementRequest(
            @NotBlank String description,
            @Min(1) @Max(5) int weight,
            @NotNull Boolean mandatory
    ) {
        VacancyService.RequirementCommand command() {
            return new VacancyService.RequirementCommand(description, weight, mandatory);
        }
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }
}
