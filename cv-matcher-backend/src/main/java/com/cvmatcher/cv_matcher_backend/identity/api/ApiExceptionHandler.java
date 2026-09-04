package com.cvmatcher.cv_matcher_backend.identity.api;

import com.cvmatcher.cv_matcher_backend.identity.application.PasswordPolicyException;
import com.cvmatcher.cv_matcher_backend.identity.insfrastructure.observability.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(PasswordPolicyException.class)
    ResponseEntity<ApiError> handlePasswordPolicy(HttpServletRequest request) {
        return error(
                HttpStatus.BAD_REQUEST,
                "PASSWORD_POLICY_VIOLATION",
                "La contraseña debe tener al menos 8 caracteres, una mayúscula, una minúscula y un número.",
                request
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> handleInvalidArgument(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "La solicitud no es válida.", request);
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<ApiError> handleAuthenticationFailure(HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Credenciales inválidas.", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleRequestValidation(HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Revise los datos enviados.", request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> handleConflict(HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "CONFLICT", "La operación no puede completarse.", request);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiError> handleDuplicateKey(HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "CONFLICT", "La operación no puede completarse.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Ocurrió un error inesperado.", request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), code, message, Instant.now(), request.getRequestURI(), correlationId(request)));
    }

    private UUID correlationId(HttpServletRequest request) {
        var value = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        return value instanceof UUID correlationId ? correlationId : null;
    }

}
