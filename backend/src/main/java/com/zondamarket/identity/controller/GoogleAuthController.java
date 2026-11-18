package com.zondamarket.identity.controller;

import com.zondamarket.identity.dto.AccessTokenResponse;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.identity.service.GoogleAccounts;
import com.zondamarket.identity.service.GoogleAuthFlow;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Identidad con Google")
class GoogleAuthController {
    private static final String BROWSER = "__Host-zonda-oauth";
    private static final String RETURN = "/oauth/retorno";

    record Options(boolean enabled) {}
    record GoogleLinkStatus(boolean linked) {}
    record LinkRequest(@NotBlank @Size(max = 72) String password) {}
    record LinkResponse(URI authorizationUrl) {}
    record ExchangeRequest(@NotBlank @Size(max = 43) String code) {}

    private final GoogleAuthFlow flow;
    private final GoogleAccounts accounts;

    GoogleAuthController(GoogleAuthFlow flow, GoogleAccounts accounts) {
        this.flow = flow;
        this.accounts = accounts;
    }

    @GetMapping("/api/v1/auth/oauth/google")
    Options options(HttpServletRequest request) {
        return new Options(flow.enabled(host(request)));
    }

    @GetMapping("/oauth2/authorization/google")
    ResponseEntity<Void> start(HttpServletRequest request) {
        if (!flow.enabled(host(request))) {
            return redirect(URI.create(RETURN + "#error=unavailable"));
        }
        var start = flow.startLogin(host(request));
        return ResponseEntity.status(HttpStatus.FOUND).location(start.url())
                .header(HttpHeaders.SET_COOKIE, browserCookie(start.browser(), GoogleAuthFlow.ATTEMPT_AGE))
                .header("Referrer-Policy", "no-referrer").build();
    }

    @GetMapping("/login/oauth2/code/google")
    ResponseEntity<Void> callback(HttpServletRequest request,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "error", required = false) String error,
            @CookieValue(name = BROWSER, required = false) String browser) {
        var result = flow.callback(host(request), state, browser, code, error);
        if (result.code() == null) {
            return redirect(URI.create(RETURN + "#error=" + result.error()));
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(RETURN + "#code=" + result.code()))
                .header(HttpHeaders.SET_COOKIE, browserCookie(browser, GoogleAuthFlow.EXCHANGE_AGE))
                .header("Referrer-Policy", "no-referrer").build();
    }

    @GetMapping("/api/v1/me/identities/google")
    @SecurityRequirement(name = "bearerAuth")
    GoogleLinkStatus status(@AuthenticationPrincipal AccountResponse account) {
        return new GoogleLinkStatus(accounts.isLinked(account.id()));
    }

    @PostMapping("/api/v1/me/identities/google/start")
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<LinkResponse> startLink(HttpServletRequest request, @AuthenticationPrincipal AccountResponse account,
            @Valid @RequestBody LinkRequest link,
            @CookieValue(name = AuthController.COOKIE, required = false) String refresh) {
        var start = flow.startLink(host(request), account.id(), link.password(), refresh);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, browserCookie(start.browser(), GoogleAuthFlow.ATTEMPT_AGE))
                .body(new LinkResponse(start.url()));
    }

    @PostMapping("/api/v1/auth/oauth/exchange")
    ResponseEntity<AccessTokenResponse> exchange(HttpServletRequest request,
            @Valid @RequestBody ExchangeRequest exchange,
            @CookieValue(name = BROWSER, required = false) String browser,
            @CookieValue(name = AuthController.COOKIE, required = false) String refresh) {
        var issued = flow.exchange(host(request), exchange.code(), browser, refresh);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
                AuthController.cookie(issued.refresh(), issued.cookieAge()), browserCookie("", Duration.ZERO))
                .body(issued.access());
    }

    private static String host(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.HOST);
    }

    private static ResponseEntity<Void> redirect(URI location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(location)
                .header("Referrer-Policy", "no-referrer").build();
    }

    private static String browserCookie(String browser, Duration age) {
        return ResponseCookie.from(BROWSER, browser).httpOnly(true).secure(true).sameSite("Lax")
                .path("/").maxAge(age).build().toString();
    }
}
