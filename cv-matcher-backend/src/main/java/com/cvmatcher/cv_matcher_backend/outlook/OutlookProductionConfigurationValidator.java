package com.cvmatcher.cv_matcher_backend.outlook;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Base64;

@Component
final class OutlookProductionConfigurationValidator implements SmartInitializingSingleton {
    private final OutlookProperties properties;
    private final Environment environment;

    OutlookProductionConfigurationValidator(OutlookProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!environment.matchesProfiles("prod")) return;
        if (blank(properties.tenantId()) || blank(properties.clientId()) || blank(properties.clientSecret())
                || !microsoftAuthority(properties.authority()) || !httpsUrl(properties.redirectUri()) || !httpsUrl(properties.appBaseUrl())
                || properties.tokenEncryptionKeyVersion() <= 0 || properties.connectTimeout() == null || properties.connectTimeout().isNegative() || properties.connectTimeout().isZero()
                || properties.readTimeout() == null || properties.readTimeout().isNegative() || properties.readTimeout().isZero()
                || properties.maxRetries() < 1 || properties.maxRetries() > 3 || !validAes256Key(properties.tokenEncryptionKey())) {
            throw new IllegalStateException("Invalid production Outlook configuration");
        }
    }

    private static boolean microsoftAuthority(String value) {
        try {
            var uri = URI.create(value);
            return "https".equals(uri.getScheme()) && "login.microsoftonline.com".equals(uri.getHost()) && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean httpsUrl(String value) {
        try {
            var uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getQuery() == null && uri.getFragment() == null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean validAes256Key(String value) {
        try {
            return !blank(value) && Base64.getDecoder().decode(value).length == 32;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
