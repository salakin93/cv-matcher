package com.cvmatcher.cv_matcher_backend.microsoft.api;

import com.cvmatcher.cv_matcher_backend.microsoft.service.MicrosoftOAuthService;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/microsoft")
public class MicrosoftIntegrationController {

    private final MicrosoftOAuthService microsoftOAuthService;

    public MicrosoftIntegrationController(MicrosoftOAuthService microsoftOAuthService) {
        this.microsoftOAuthService = microsoftOAuthService;
    }

    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize() {
        return ResponseEntity.status(302).location(microsoftOAuthService.beginAuthorization()).build();
    }

    @GetMapping("/connection")
    public MicrosoftOAuthService.MicrosoftConnectionStatus connection() {
        return microsoftOAuthService.connectionStatus();
    }
}
