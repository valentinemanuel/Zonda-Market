package com.zondamarket.catalog.repository;

import com.zondamarket.catalog.domain.Category;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends Repository<Category, UUID> {
    Optional<Category> findByIdAndDeletedFalse(UUID id);
}
