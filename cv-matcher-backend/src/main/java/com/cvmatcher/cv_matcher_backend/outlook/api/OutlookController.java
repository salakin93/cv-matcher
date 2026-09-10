package com.cvmatcher.cv_matcher_backend.outlook.api;

import com.cvmatcher.cv_matcher_backend.identity.api.ApiError;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookException;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/integrations/outlook")
@Tag(name = "Outlook", description = "Conexión compartida de Outlook")
public class OutlookController {
    private final OutlookService service;

    public OutlookController(OutlookService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Consultar estado seguro de Outlook")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "401", description = "No autenticado", content = @Content(schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "403", description = "Sólo ADMIN", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    public OutlookService.Status status() {
        return service.status();
    }

    @PostMapping("/authorization")
    @Operation(summary = "Iniciar autorización Outlook")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "URL de autorización segura"), @ApiResponse(responseCode = "401", description = "No autenticado", content = @Content(schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "403", description = "Sólo ADMIN", content = @Content(schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "422", description = "El cuerpo debe ser un objeto vacío", content = @Content(schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "503", description = "Outlook no configurado", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    public OutlookService.Start start(@AuthenticationPrincipal UUID actor, @RequestBody Map<String, Object> body) {
        if (body == null || !body.isEmpty())
            throw new OutlookException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        return service.start(actor);
    }

    @GetMapping("/callback")
    @Operation(summary = "Completar callback OAuth Outlook")
    @ApiResponses({@ApiResponse(responseCode = "302", description = "Redirección con resultado seguro"), @ApiResponse(responseCode = "400", description = "State inválido, vencido o consumido", content = @Content(schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "502", description = "Autorización Microsoft inválida", content = @Content(schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "503", description = "Microsoft no disponible o Outlook no configurado", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    public ResponseEntity<Void> callback(@RequestParam(required = false) String state, @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String error) {
        return ResponseEntity.status(302).location(URI.create(service.completeAuthorization(state, code, error))).build();
    }
}
