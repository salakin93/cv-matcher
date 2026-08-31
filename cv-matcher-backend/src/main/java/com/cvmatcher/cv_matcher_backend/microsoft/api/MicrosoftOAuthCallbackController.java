package com.cvmatcher.cv_matcher_backend.microsoft.api;

import com.cvmatcher.cv_matcher_backend.microsoft.service.MicrosoftOAuthService;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/oauth2/callback/microsoft")
public class MicrosoftOAuthCallbackController {

    private final MicrosoftOAuthService microsoftOAuthService;

    public MicrosoftOAuthCallbackController(MicrosoftOAuthService microsoftOAuthService) {
        this.microsoftOAuthService = microsoftOAuthService;
    }

    @GetMapping
    public ResponseEntity<CallbackResponse> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {
        if (error != null || code == null || state == null) {
            return ResponseEntity.badRequest().body(CallbackResponse.failed());
        }
        try {
            microsoftOAuthService.completeAuthorization(code, state);
            return ResponseEntity.ok(CallbackResponse.connected());
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CallbackResponse.failed());
        }
    }

    private record CallbackResponse(boolean success, String message, Instant completedAt) {
        private static CallbackResponse connected() { return new CallbackResponse(true, "Microsoft connection completed", Instant.now()); }
        private static CallbackResponse failed() { return new CallbackResponse(false, "Microsoft connection could not be completed", Instant.now()); }
    }
}
