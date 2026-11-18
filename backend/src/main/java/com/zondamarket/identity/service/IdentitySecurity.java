package com.zondamarket.identity.service;

import com.zondamarket.shared.errors.ApiProblemException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
class IdentitySecurity {
    private static final String[] COOKIE_ACTIONS = {
        "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
        "/api/v1/auth/oauth/exchange", "/api/v1/me/identities/google/start"
    };

    @Bean
    Clock identityClock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain identityFilterChain(HttpSecurity http, AuthService auth, SessionTokens tokens,
            @Value("${zonda.auth.allowed-origins}") String configuredOrigins,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) throws Exception {
        Set<String> origins = Arrays.stream(configuredOrigins.split(","))
                .map(String::strip).filter(origin -> !origin.isEmpty() && !"null".equals(origin))
                .collect(Collectors.toUnmodifiableSet());
        if (origins.isEmpty()) {
            throw new IllegalArgumentException("Configurá al menos un origen web para identidad.");
        }

        AuthenticationEntryPoint entryPoint = (request, response, failure) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            errors.resolveException(request, response, null, AuthService.unauthorized());
        };
        var cookieActions = new OrRequestMatcher(Arrays.stream(COOKIE_ACTIONS)
                .map(path -> PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, path))
                .toArray(org.springframework.security.web.util.matcher.RequestMatcher[]::new));
        jakarta.servlet.Filter cookieCsrf = (request, response, chain) -> {
            var httpRequest = (HttpServletRequest) request;
            if (cookieActions.matches(httpRequest) && !trustedCookieRequest(httpRequest, origins)) {
                errors.resolveException(httpRequest, (jakarta.servlet.http.HttpServletResponse) response, null,
                        new ApiProblemException(HttpStatus.FORBIDDEN, "CSRF_REJECTED",
                                "La solicitud debe tener un origen web permitido y una cabecera CSRF."));
                return;
            }
            chain.doFilter(request, response);
        };
        return http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // Bearer routes use no ambient credentials. Cookie commands enforce their own CSRF policy.
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterAfter(cookieCsrf, CsrfFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/api/v1/me/identities/google/start").authenticated()
                        .requestMatchers(cookieActions).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/status", "/api/v1/categories/*",
                                "/api/v1/auth/oauth/google", "/oauth2/authorization/google", "/login/oauth2/code/google",
                                "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                .oauth2ResourceServer(oauth -> oauth.authenticationEntryPoint(entryPoint)
                        .jwt(jwt -> jwt.decoder(tokens::decodeAccess).jwtAuthenticationConverter(token -> {
                            var account = auth.currentAccount(token);
                            var roles = account.roles().stream()
                                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
                            return UsernamePasswordAuthenticationToken.authenticated(account, null, roles);
                        })))
                .build();
    }

    private static boolean trustedCookieRequest(HttpServletRequest request, Set<String> origins) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        return origin != null && origins.contains(origin) && "1".equals(request.getHeader("X-Zonda-CSRF"));
    }
}
