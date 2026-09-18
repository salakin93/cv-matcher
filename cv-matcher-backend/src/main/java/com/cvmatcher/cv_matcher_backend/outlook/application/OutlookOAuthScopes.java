package com.cvmatcher.cv_matcher_backend.outlook.application;

import java.util.Arrays;
import java.util.List;

public final class OutlookOAuthScopes {
    private static final List<String> REQUESTED = List.of("openid", "profile", "offline_access", "Mail.Read");

    private OutlookOAuthScopes() {
    }

    public static String authorizationValue() {
        return String.join(" ", REQUESTED);
    }

    public static boolean matches(String[] grantedScopes) {
        return grantedScopes.length == REQUESTED.size() && Arrays.stream(grantedScopes).allMatch(REQUESTED::contains);
    }
}
