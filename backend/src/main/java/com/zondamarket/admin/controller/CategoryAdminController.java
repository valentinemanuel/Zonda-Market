package com.zondamarket.admin.controller;

import static com.zondamarket.shared.web.CorrelationIdFilter.responseTraceId;

import com.zondamarket.admin.dto.CategoryAdminDtos.ArchiveRequest;
import com.zondamarket.admin.dto.CategoryAdminDtos.CategoryAdminRequest;
import com.zondamarket.admin.dto.CategoryAdminDtos.CategoryRow;
import com.zondamarket.admin.service.CategoryAdminService;
import com.zondamarket.identity.dto.AccountResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/categories")
@Tag(name = "Administración de categorías")
@SecurityRequirement(name = "bearerAuth")
class CategoryAdminController {
    private final CategoryAdminService categories;

    CategoryAdminController(CategoryAdminService categories) {
        this.categories = categories;
    }

    @GetMapping
    ResponseEntity<List<CategoryRow>> list() {
        return privateResponse(HttpStatus.OK, categories.categories());
    }

    @PostMapping
    ResponseEntity<CategoryRow> create(@Valid @RequestBody CategoryAdminRequest request,
            @AuthenticationPrincipal AccountResponse actor, HttpServletResponse response) {
        return privateResponse(HttpStatus.CREATED, categories.create(request, actor.id(), responseTraceId(response)));
    }

    @PatchMapping("/{id}")
    ResponseEntity<CategoryRow> edit(@PathVariable UUID id, @Valid @RequestBody CategoryAdminRequest request,
            @AuthenticationPrincipal AccountResponse actor, HttpServletResponse response) {
        return privateResponse(HttpStatus.OK, categories.edit(id, request, actor.id(), responseTraceId(response)));
    }

    @PostMapping("/{id}/archive")
    ResponseEntity<CategoryRow> archive(@PathVariable UUID id, @Valid @RequestBody ArchiveRequest request) {
        return privateResponse(HttpStatus.OK, categories.archive(id, request.expectedVersion()));
    }

    private static <T> ResponseEntity<T> privateResponse(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
