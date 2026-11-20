package com.zondamarket.catalog.service;

import com.zondamarket.catalog.dto.CategoryResponse;
import com.zondamarket.catalog.dto.DiscoveryDtos.CategoryNode;
import com.zondamarket.catalog.repository.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class CategoryService {
    private final CategoryRepository categories;

    public CategoryService(CategoryRepository categories) {
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public Optional<CategoryResponse> findPublicCategory(UUID id) {
        return categories.findByIdAndDeletedFalse(id)
                .map(CategoryResponse::from);
    }

    @Transactional(readOnly = true)
    public List<CategoryNode> tree() {
        Map<UUID, List<CategoryResponse>> children = new HashMap<>();
        for (var category : categories.findAllByDeletedFalseOrderByNameAscIdAsc()) {
            children.computeIfAbsent(category.getParentId(), key -> new ArrayList<>())
                    .add(CategoryResponse.from(category));
        }
        return branch(null, children);
    }

    private static List<CategoryNode> branch(UUID parent, Map<UUID, List<CategoryResponse>> children) {
        return children.getOrDefault(parent, List.of()).stream()
                .map(category -> new CategoryNode(category, branch(category.id(), children)))
                .toList();
    }
}
