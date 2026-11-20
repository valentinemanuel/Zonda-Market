package com.zondamarket.catalog.controller;

import com.zondamarket.catalog.dto.CategoryResponse;
import com.zondamarket.catalog.dto.DiscoveryDtos.CategoryNode;
import com.zondamarket.catalog.service.CategoryService;
import com.zondamarket.shared.errors.ApiProblemException;
import com.zondamarket.shared.errors.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categorías")
public class CategoryController {
    private final CategoryService categories;

    public CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    @Operation(summary = "Consultar el árbol público de categorías")
    List<CategoryNode> tree() {
        return categories.tree();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar una categoría activa", description = "Lectura pública por UUID.")
    @ApiResponse(responseCode = "200", description = "Categoría visible",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CategoryResponse.class)))
    @ApiResponse(responseCode = "400", description = "UUID inválido",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "404", description = "Categoría ausente o archivada",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemResponse.class)))
    public CategoryResponse getCategory(
            @Parameter(schema = @Schema(type = "string", format = "uuid"))
            @PathVariable @Pattern(
                    regexp = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                    message = "Usá un UUID válido.") String id) {
        return categories.findPublicCategory(UUID.fromString(id))
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "La categoría no está disponible."));
    }
}
