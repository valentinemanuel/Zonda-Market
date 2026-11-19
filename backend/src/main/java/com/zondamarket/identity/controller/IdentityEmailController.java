package com.zondamarket.identity.controller;

import com.zondamarket.identity.domain.EmailPurpose;
import com.zondamarket.identity.dto.EmailLinkRequests;
import com.zondamarket.identity.service.IdentityEmailService;
import com.zondamarket.shared.errors.ApiProblemException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Identidad")
class IdentityEmailController {
    private final IdentityEmailService email;

    IdentityEmailController(IdentityEmailService email) {
        this.email = email;
    }

    @PostMapping("/email-verification/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Solicitar un enlace de verificación")
    @ApiResponse(responseCode = "202", description = "Solicitud recibida")
    void requestVerification(@Valid @RequestBody EmailLinkRequests.Request form, HttpServletRequest request) {
        email.request(EmailPurpose.VERIFY_EMAIL, form.email(), request.getRemoteAddr());
    }

    @PostMapping("/email-verification/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Confirmar el correo con un enlace de un solo uso")
    @ApiResponse(responseCode = "204", description = "Correo verificado")
    void verify(@Valid @RequestBody EmailLinkRequests.Verification form, HttpServletRequest request) {
        confirm(EmailPurpose.VERIFY_EMAIL, form.token(), null, request);
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Solicitar un enlace de recuperación")
    @ApiResponse(responseCode = "202", description = "Solicitud recibida")
    void requestReset(@Valid @RequestBody EmailLinkRequests.Request form, HttpServletRequest request) {
        email.request(EmailPurpose.RESET_PASSWORD, form.email(), request.getRemoteAddr());
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Restablecer la contraseña y revocar las sesiones de esa cuenta")
    @ApiResponse(responseCode = "204", description = "Contraseña actualizada")
    void reset(@Valid @RequestBody EmailLinkRequests.Reset form, HttpServletRequest request) {
        confirm(EmailPurpose.RESET_PASSWORD, form.token(), form.password(), request);
    }

    private void confirm(EmailPurpose purpose, String token, String password, HttpServletRequest request) {
        if (!email.allowConfirmation(request.getRemoteAddr())) {
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_LINK_RATE_LIMIT",
                    "Hubo demasiados intentos. Volvé a intentar más tarde.");
        }
        email.confirm(purpose, token, password);
    }
}
