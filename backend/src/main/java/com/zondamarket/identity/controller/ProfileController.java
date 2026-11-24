package com.zondamarket.identity.controller;

import static com.zondamarket.shared.web.CorrelationIdFilter.responseTraceId;

import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.identity.dto.ProfileDtos.AddressPatch;
import com.zondamarket.identity.dto.ProfileDtos.AddressRequest;
import com.zondamarket.identity.dto.ProfileDtos.AddressResponse;
import com.zondamarket.identity.dto.ProfileDtos.ProfileUpdate;
import com.zondamarket.identity.dto.ProfileDtos.SellerActivationRequest;
import com.zondamarket.identity.dto.ProfileDtos.SellerResponse;
import com.zondamarket.identity.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Perfiles")
class ProfileController {
    private final ProfileService profiles;

    ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @PatchMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Actualizar el nombre de la cuenta autenticada")
    AccountResponse update(@AuthenticationPrincipal AccountResponse account, @Valid @RequestBody ProfileUpdate update) {
        return profiles.update(account.id(), update);
    }

    @GetMapping("/me/addresses")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Listar mis direcciones activas")
    List<AddressResponse> addresses(@AuthenticationPrincipal AccountResponse account) {
        return profiles.addresses(account.id());
    }

    @PostMapping("/me/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Agregar una dirección en Argentina")
    AddressResponse add(@AuthenticationPrincipal AccountResponse account, @Valid @RequestBody AddressRequest request) {
        return profiles.addAddress(account.id(), request);
    }

    @PatchMapping("/me/addresses/{id}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Actualizar mi dirección")
    AddressResponse updateAddress(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id,
                                  @Valid @RequestBody AddressPatch request) {
        return profiles.updateAddress(account.id(), id, request);
    }

    @DeleteMapping("/me/addresses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Dar de baja mi dirección")
    void remove(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id) {
        profiles.removeAddress(account.id(), id);
    }

    @PostMapping("/me/seller-profile")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Activar mi perfil de vendedor con correo verificado")
    SellerResponse activate(@AuthenticationPrincipal AccountResponse account, @Valid @RequestBody SellerActivationRequest request,
                            HttpServletResponse response) {
        return profiles.activate(account.id(), request, responseTraceId(response));
    }

    @GetMapping("/me/seller-profile")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Consultar mi perfil de vendedor")
    SellerResponse ownSeller(@AuthenticationPrincipal AccountResponse account) {
        return profiles.ownSeller(account.id());
    }

    @GetMapping("/sellers/{slug}")
    @Operation(summary = "Consultar la ficha pública de un vendedor")
    SellerResponse publicSeller(@PathVariable String slug) {
        return profiles.publicSeller(slug);
    }
}
