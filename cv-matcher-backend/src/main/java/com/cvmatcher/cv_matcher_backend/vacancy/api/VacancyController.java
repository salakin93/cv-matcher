package com.cvmatcher.cv_matcher_backend.vacancy.api;

import com.cvmatcher.cv_matcher_backend.identity.api.ApiError;
import com.cvmatcher.cv_matcher_backend.vacancy.application.VacancyException;
import com.cvmatcher.cv_matcher_backend.vacancy.application.VacancyService;
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
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/vacancies")
@Tag(name = "Vacancies", description = "Gestión compartida de vacantes y requisitos ponderados")
@SecurityRequirement(name = "bearerAuth")
public class VacancyController {
    private final VacancyService service;

    public VacancyController(VacancyService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear vacante", description = "Crea una vacante activa con un rango de recepción en America/La_Paz.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Vacante creada"),
            @ApiResponse(responseCode = "401", description = "Bearer inválido o sesión revocada", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol insuficiente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Solicitud inválida", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public VacancyService.VacancyDetail create(
            @org.springframework.security.core.annotation.AuthenticationPrincipal UUID actorId,
            @Valid @RequestBody VacancyWriteRequest request
    ) {
        if (request.getExpectedVersion() != 0) throw new VacancyException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        return service.create(actorId, request.command());
    }

    @GetMapping
    @Operation(summary = "Listar vacantes", description = "Devuelve vacantes compartidas paginadas; por defecto sólo las activas.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página de vacantes"),
            @ApiResponse(responseCode = "401", description = "Bearer inválido o sesión revocada", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol insuficiente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Filtros o paginación inválidos", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public VacancyService.VacancyPage list(
            @Parameter(in = ParameterIn.QUERY) @RequestParam(required = false) VacancyService.Status status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return service.list(status, page, size);
    }

    @GetMapping("/{vacancyId}")
    @Operation(summary = "Consultar una vacante")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Detalle de vacante"),
            @ApiResponse(responseCode = "401", description = "Bearer inválido o sesión revocada", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol insuficiente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Vacante inexistente", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public VacancyService.VacancyDetail get(@PathVariable UUID vacancyId) {
        return service.get(vacancyId);
    }

    @PutMapping("/{vacancyId}")
    @Operation(summary = "Editar vacante activa", description = "Reemplaza de forma atómica los requisitos si expectedVersion coincide.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Vacante actualizada"),
            @ApiResponse(responseCode = "401", description = "Bearer inválido o sesión revocada", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol insuficiente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Vacante inexistente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Vacante archivada o versión desactualizada", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Solicitud inválida", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public VacancyService.VacancyDetail update(
            @org.springframework.security.core.annotation.AuthenticationPrincipal UUID actorId,
            @PathVariable UUID vacancyId,
            @Valid @RequestBody VacancyWriteRequest request
    ) {
        return service.update(actorId, vacancyId, request.command());
    }

    @PostMapping("/{vacancyId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Archivar vacante", description = "Archiva una vacante activa cuando expectedVersion coincide. Repetir la transición ya satisfecha no produce cambios.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Vacante archivada o ya archivada"),
            @ApiResponse(responseCode = "401", description = "Bearer inválido o sesión revocada", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol insuficiente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Vacante inexistente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Versión desactualizada o transición no permitida", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Solicitud inválida", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void archive(
            @org.springframework.security.core.annotation.AuthenticationPrincipal UUID actorId,
            @PathVariable UUID vacancyId,
            @Valid @RequestBody VersionRequest request
    ) {
        service.archive(actorId, vacancyId, request.getExpectedVersion());
    }

    @PostMapping("/{vacancyId}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Reactivar vacante", description = "Reactiva una vacante archivada cuando expectedVersion coincide. Repetir la transición ya satisfecha no produce cambios.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Vacante reactivada o ya activa"),
            @ApiResponse(responseCode = "401", description = "Bearer inválido o sesión revocada", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol insuficiente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Vacante inexistente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Versión desactualizada o transición no permitida", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Solicitud inválida", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void reactivate(
            @org.springframework.security.core.annotation.AuthenticationPrincipal UUID actorId,
            @PathVariable UUID vacancyId,
            @Valid @RequestBody VersionRequest request
    ) {
        service.reactivate(actorId, vacancyId, request.getExpectedVersion());
    }

    static final class VacancyWriteRequest {
        private String title;
        private String description;
        private LocalDate dateFrom;
        private LocalDate dateTo;
        private Long expectedVersion;
        private List<@Valid RequirementRequest> requirements;
        private boolean unknownField;

        @NotBlank @Size(max = 160) public String getTitle() { return title; }
        @NotBlank @Size(max = 10_000) public String getDescription() { return description; }
        @NotNull public LocalDate getDateFrom() { return dateFrom; }
        @NotNull public LocalDate getDateTo() { return dateTo; }
        @NotNull @Min(0) public Long getExpectedVersion() { return expectedVersion; }
        @NotEmpty @Size(max = 30) public List<@Valid RequirementRequest> getRequirements() { return requirements; }

        @JsonSetter("title") public void setTitle(String value) { title = trim(value); }
        @JsonSetter("description") public void setDescription(String value) { description = trim(value); }
        @JsonSetter("dateFrom") public void setDateFrom(LocalDate value) { dateFrom = value; }
        @JsonSetter("dateTo") public void setDateTo(LocalDate value) { dateTo = value; }
        @JsonSetter("expectedVersion") public void setExpectedVersion(Long value) { expectedVersion = value; }
        @JsonSetter("requirements") public void setRequirements(List<RequirementRequest> value) { requirements = value; }
        @JsonAnySetter public void unknown(String name, Object value) { unknownField = true; }

        @AssertFalse(message = "Los campos adicionales no están permitidos.") @JsonIgnore public boolean isUnknownField() { return unknownField; }
        @AssertTrue(message = "dateFrom debe ser anterior o igual a dateTo.") @JsonIgnore public boolean isDateRangeValid() {
            return dateFrom == null || dateTo == null || !dateFrom.isAfter(dateTo);
        }

        VacancyService.VacancyCommand command() {
            return new VacancyService.VacancyCommand(title, description, dateFrom, dateTo, expectedVersion, requirements.stream().map(RequirementRequest::command).toList());
        }
    }

    static final class RequirementRequest {
        private String description;
        private Integer weight;
        private Boolean mandatory;
        private boolean unknownField;

        @NotBlank @Size(max = 1_000) public String getDescription() { return description; }
        @NotNull @Min(1) @Max(5) public Integer getWeight() { return weight; }
        @NotNull public Boolean getMandatory() { return mandatory; }
        @JsonSetter("description") public void setDescription(String value) { description = trim(value); }
        @JsonSetter("weight") public void setWeight(Integer value) { weight = value; }
        @JsonSetter("mandatory") public void setMandatory(Boolean value) { mandatory = value; }
        @JsonAnySetter public void unknown(String name, Object value) { unknownField = true; }
        @AssertFalse(message = "Los campos adicionales no están permitidos.") @JsonIgnore public boolean isUnknownField() { return unknownField; }

        VacancyService.RequirementCommand command() { return new VacancyService.RequirementCommand(description, weight, mandatory); }
    }

    static final class VersionRequest {
        private Long expectedVersion;
        private boolean unknownField;

        @NotNull @Min(0) public Long getExpectedVersion() { return expectedVersion; }
        @JsonSetter("expectedVersion") public void setExpectedVersion(Long value) { expectedVersion = value; }
        @JsonAnySetter public void unknown(String name, Object value) { unknownField = true; }
        @AssertFalse(message = "Los campos adicionales no están permitidos.") @JsonIgnore public boolean isUnknownField() { return unknownField; }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
