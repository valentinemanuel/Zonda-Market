package com.zondamarket.catalog.service;

import com.zondamarket.catalog.dto.CategoryResponse;
import com.zondamarket.catalog.repository.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
