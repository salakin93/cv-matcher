package com.cvmatcher.cv_matcher_backend.job.api;

import com.cvmatcher.cv_matcher_backend.identity.api.ApiError;
import com.cvmatcher.cv_matcher_backend.job.application.JobService;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
public class JobController {
    private final JobService jobs;
    public JobController(JobService jobs) { this.jobs = jobs; }

    @PostMapping("/vacancies/{vacancyId}/report-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Encolar reporte", description = "Persiste un trabajo durable en estado QUEUED; no ejecuta procesamiento durante la solicitud HTTP.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Trabajo encolado", content = @Content(schema = @Schema(implementation = JobService.JobAccepted.class))),
            @ApiResponse(responseCode = "401", description = "Bearer inválido o sesión revocada", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol insuficiente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Vacante inexistente", content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = "{\"status\":404,\"code\":\"VACANCY_NOT_FOUND\",\"message\":\"La operación no puede completarse.\"}"))),
            @ApiResponse(responseCode = "409", description = "Vacante archivada o trabajo activo existente", content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = "{\"status\":409,\"code\":\"ACTIVE_JOB_EXISTS\",\"message\":\"La operación no puede completarse.\"}"))),
            @ApiResponse(responseCode = "422", description = "Body distinto de {} o identificador inválido", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public JobService.JobAccepted enqueue(@org.springframework.security.core.annotation.AuthenticationPrincipal UUID actorId, @PathVariable UUID vacancyId, @NotNull @Valid @RequestBody EmptyRequest request) { return jobs.enqueue(actorId, vacancyId); }

    @GetMapping("/vacancies/{vacancyId}/report-jobs")
    @Operation(summary = "Listar trabajos de una vacante", description = "Página compartida ordenada por createdAt descendente e id ascendente. Los estados son QUEUED, DISCOVERING, INGESTING_DOCUMENTS, ANALYZING, COMPLETED, COMPLETED_WITH_WARNINGS, FAILED, REAUTHORIZATION_REQUIRED y CANCELLED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página de trabajos", content = @Content(schema = @Schema(implementation = JobService.JobPage.class))),
            @ApiResponse(responseCode = "401", description = "Bearer inválido o sesión revocada", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol insuficiente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Vacante inexistente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Filtro, UUID o paginación inválidos", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public JobService.JobPage list(@PathVariable UUID vacancyId, @Parameter(in = ParameterIn.QUERY, description = "Filtro exacto de estado") @RequestParam(required = false) JobService.Status status, @Parameter(in = ParameterIn.QUERY, description = "Página desde 0") @RequestParam(defaultValue = "0") @Min(0) int page, @Parameter(in = ParameterIn.QUERY, description = "Elementos por página, de 1 a 100") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) { return jobs.list(vacancyId, status, page, size); }

    @GetMapping("/report-jobs/{jobId}")
    @Operation(summary = "Consultar un trabajo", description = "Devuelve el snapshot de la vacante y requisitos; nunca expone lease, solicitante, sesiones, tokens ni documentos.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Detalle de trabajo", content = @Content(schema = @Schema(implementation = JobService.JobDetail.class))),
            @ApiResponse(responseCode = "401", description = "Bearer inválido o sesión revocada", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol insuficiente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Trabajo inexistente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "UUID inválido", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public JobService.JobDetail get(@PathVariable UUID jobId) { return jobs.get(jobId); }

    @PostMapping("/report-jobs/{jobId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancelar trabajo activo")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Trabajo cancelado o ya cancelado"),
            @ApiResponse(responseCode = "401", description = "Bearer inválido o sesión revocada", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol insuficiente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Trabajo inexistente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Trabajo terminal no cancelable", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Body distinto de {} o UUID inválido", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void cancel(@org.springframework.security.core.annotation.AuthenticationPrincipal UUID actorId, @PathVariable UUID jobId, @NotNull @Valid @RequestBody EmptyRequest request) { jobs.cancel(actorId, jobId); }

    @PostMapping("/report-jobs/{jobId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Reintentar trabajo terminal reintentable")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Nuevo trabajo QUEUED desde snapshot previo", content = @Content(schema = @Schema(implementation = JobService.JobAccepted.class))),
            @ApiResponse(responseCode = "401", description = "Bearer inválido o sesión revocada", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Rol insuficiente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Trabajo inexistente", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Trabajo no reintentable o existe un trabajo activo", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Body distinto de {} o UUID inválido", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public JobService.JobAccepted retry(@org.springframework.security.core.annotation.AuthenticationPrincipal UUID actorId, @PathVariable UUID jobId, @NotNull @Valid @RequestBody EmptyRequest request) { return jobs.retry(actorId, jobId); }

    static final class EmptyRequest {
        private boolean unknownField;
        @JsonAnySetter public void unknown(String name, Object value) { unknownField = true; }
        @AssertFalse @JsonIgnore public boolean isUnknownField() { return unknownField; }
    }
}
