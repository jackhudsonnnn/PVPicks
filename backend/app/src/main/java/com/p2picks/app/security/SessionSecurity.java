package com.p2picks.app.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

@Configuration(proxyBeanMethods = false)
class SessionSecurity {

    static final String ISSUER = "p2picks-dev";
    private static final OAuth2Error INVALID_SESSION = new OAuth2Error("invalid_token", "Invalid session", null);

    @Bean
    SecretKey jwtKey(@Value("${PVPICKS_DEV_JWT_SECRET}") String encoded) {
        byte[] key = Base64.getDecoder().decode(encoded);
        if (key.length < 32) {
            throw new IllegalArgumentException("PVPICKS_DEV_JWT_SECRET must encode at least 32 bytes");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtKey, JdbcTemplate jdbc) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> sessionValidator = jwt -> {
            UUID sessionId;
            UUID userId;
            try {
                sessionId = UUID.fromString(jwt.getId());
                userId = UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException | NullPointerException exception) {
                return OAuth2TokenValidatorResult.failure(INVALID_SESSION);
            }
            if (jwt.getExpiresAt() == null || jwt.getIssuedAt() == null) {
                return OAuth2TokenValidatorResult.failure(INVALID_SESSION);
            }
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM auth_sessions s JOIN users u ON u.id = s.user_id
                    WHERE s.id = ? AND s.user_id = ? AND s.revoked_at IS NULL
                      AND s.expires_at > now() AND u.status = 'ACTIVE'
                    """, Integer.class, sessionId, userId);
            return count != null && count == 1
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(INVALID_SESSION);
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(ISSUER), sessionValidator));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        repository.setCookieCustomizer(cookie -> cookie.path("/").httpOnly(true).sameSite("Strict").secure(false));
        return repository;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtDecoder decoder, CsrfTokenRepository csrfRepository, JdbcTemplate jdbc,
            Environment environment,
            @Value("${PVPICKS_DEV_ORIGIN:http://127.0.0.1:8080}") String allowedOrigin) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"unauthorized\",\"message\":\"Authentication required or credentials invalid.\"}");
        };
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.disable())
                // The session cookie authenticates every request, so Spring would treat each one as a new
                // login and rotate the CSRF token. Login and logout rotate it explicitly instead.
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy()))
                .authorizeHttpRequests(authorize -> {
                    String[] profiles = environment.getActiveProfiles();
                    if (profiles.length == 1 && profiles[0].equals("dev")) {
                        authorize.requestMatchers(HttpMethod.GET, "/docs", "/docs/**").permitAll()
                                .requestMatchers(HttpMethod.HEAD, "/docs", "/docs/**").permitAll();
                    } else {
                        authorize.requestMatchers("/docs", "/docs/**").denyAll();
                    }
                    authorize.requestMatchers(HttpMethod.GET, "/auth/csrf", "/actuator/health").permitAll()
                            .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                            .requestMatchers(HttpMethod.GET, "/me").authenticated()
                            .requestMatchers(HttpMethod.HEAD, "/me").authenticated()
                            .requestMatchers(HttpMethod.POST, "/auth/logout").authenticated()
                            .requestMatchers("/admin", "/admin/**").access((authentication, context) ->
                                    new AuthorizationDecision(isActiveRole(authentication.get(), jdbc, "ADMIN")))
                            .anyRequest().access((authentication, context) ->
                                    new AuthorizationDecision(isActiveRole(authentication.get(), jdbc, "PLAYER")));
                })
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .addFilterBefore(new DevOriginFilter(allowedOrigin), org.springframework.security.web.csrf.CsrfFilter.class)
                .addFilterAfter(new JwtCookieFilter(decoder, unauthorized), org.springframework.security.web.csrf.CsrfFilter.class)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"code\":\"forbidden\",\"message\":\"Request forbidden.\"}");
                        }));
        return http.build();
    }

    private static boolean isActiveRole(Authentication authentication, JdbcTemplate jdbc, String role) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication) || !authentication.isAuthenticated()) {
            return false;
        }
        Jwt jwt = jwtAuthentication.getToken();
        if (jwt.getSubject() == null || jwt.getId() == null) {
            return false;
        }
        UUID userId;
        UUID sessionId;
        try {
            userId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException exception) {
            return false;
        }
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM auth_sessions s JOIN users u ON u.id = s.user_id
                    WHERE s.id = ? AND s.user_id = ? AND s.revoked_at IS NULL
                      AND s.expires_at > now() AND u.status = 'ACTIVE' AND u.role = ?
                )
                """, Boolean.class, sessionId, userId, role);
        return Objects.requireNonNull(allowed, "Role authorization query returned null");
    }
}
