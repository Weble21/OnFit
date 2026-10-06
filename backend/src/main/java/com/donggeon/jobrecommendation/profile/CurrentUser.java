package com.donggeon.jobrecommendation.profile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CurrentUser {
    private final boolean production;

    public CurrentUser(Environment environment) {
        production = environment.acceptsProfiles(Profiles.of("prod"));
    }

    public String identity() {
        if (!production) return "demo@onfit.local";
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()
                || token.getToken().getIssuer() == null || token.getToken().getSubject() == null
                || token.getToken().getSubject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        // Identity follows immutable issuer + subject, never an editable/unverified email claim.
        String key = token.getToken().getIssuer() + "\n" + token.getToken().getSubject();
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
            return "oidc." + digest + "@onfit.invalid";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
