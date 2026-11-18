package com.zondamarket.identity.controller;

import com.zondamarket.identity.dto.AccessTokenResponse;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.identity.dto.LoginRequest;
import com.zondamarket.identity.dto.RegisterRequest;
import com.zondamarket.identity.service.AuthService;
import com.zondamarket.identity.service.AuthService.IssuedSession;
import com.zondamarket.shared.errors.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Identidad")
class AuthController {
    private static final String COOKIE = "__Host-zonda-refresh";

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/auth/register")
    @Operation(summary = "Crear cuenta con rol USER e iniciar sesión")
    @ApiResponse(responseCode = "201", description = "Cuenta creada y sesión iniciada")
    @ApiResponse(responseCode = "400", description = "Datos inválidos",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "403", description = "Origin o cabecera CSRF inválidos",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "409", description = "Correo ya registrado",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemResponse.class)))
    ResponseEntity<AccessTokenResponse> register(@Valid @RequestBody RegisterRequest request,
            @CookieValue(name = COOKIE, required = false) String refresh) {
        return session(HttpStatus.CREATED, auth.register(request, refresh));
    }

    @PostMapping("/auth/login")
    @Operation(summary = "Iniciar sesión con correo y contraseña")
    @ApiResponse(responseCode = "200", description = "Sesión iniciada")
    @ApiResponse(responseCode = "400", description = "Datos inválidos",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "401", description = "Credenciales inválidas",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "403", description = "Origin o cabecera CSRF inválidos",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemResponse.class)))
    ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest request,
            @CookieValue(name = COOKIE, required = false) String refresh) {
        return session(HttpStatus.OK, auth.login(request, refresh));
    }

    @PostMapping("/auth/refresh")
    @Operation(summary = "Renovar el acceso y rotar la cookie")
    @ApiResponse(responseCode = "200", description = "Sesión renovada")
    @ApiResponse(responseCode = "401", description = "Sesión ausente, vencida, revocada o reutilizada",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "403", description = "Origin o cabecera CSRF inválidos",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemResponse.class)))
    ResponseEntity<AccessTokenResponse> refresh(@CookieValue(name = COOKIE, required = false) String refresh,
            HttpServletResponse response) {
        var issued = auth.refresh(refresh);
        if (issued.isEmpty()) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO));
            throw AuthService.unauthorized();
        }
        return session(HttpStatus.OK, issued.orElseThrow());
    }

    @PostMapping("/auth/logout")
    @Operation(summary = "Revocar la familia actual y eliminar la cookie")
    @ApiResponse(responseCode = "204", description = "Sesión cerrada", content = @Content)
    @ApiResponse(responseCode = "403", description = "Origin o cabecera CSRF inválidos",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemResponse.class)))
    ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String refresh) {
        auth.logout(refresh);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO)).build();
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Consultar la cuenta asociada al Bearer")
    @ApiResponse(responseCode = "200", description = "Cuenta autenticada")
    @ApiResponse(responseCode = "401", description = "Bearer ausente, inválido, vencido o revocado",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemResponse.class)))
    AccountResponse me(@AuthenticationPrincipal AccountResponse account) {
        return account;
    }

    private static ResponseEntity<AccessTokenResponse> session(HttpStatus status, IssuedSession issued) {
        return ResponseEntity.status(status).header(HttpHeaders.SET_COOKIE, cookie(issued.refresh(), issued.cookieAge()))
                .body(issued.access());
    }

    private static String cookie(String token, Duration maxAge) {
        return ResponseCookie.from(COOKIE, token).httpOnly(true).secure(true).sameSite("Strict")
                .path("/").maxAge(maxAge).build().toString();
    }
}
