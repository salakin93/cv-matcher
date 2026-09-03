package com.cvmatcher.cv_matcher_backend.identity.api;

import com.cvmatcher.cv_matcher_backend.identity.application.PasswordPolicyException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

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

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Ocurrió un error inesperado.", request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), code, message, Instant.now(), request.getRequestURI()));
    }

    record ApiError(int status, String code, String message, Instant timestamp, String path) {
    }
}
