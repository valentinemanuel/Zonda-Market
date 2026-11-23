package com.zondamarket.admin.service;

import com.zondamarket.admin.dto.CategoryAdminDtos.CategoryAdminRequest;
import com.zondamarket.admin.dto.CategoryAdminDtos.CategoryRow;
import com.zondamarket.admin.repository.CategoryAdminStore;
import com.zondamarket.catalog.repository.CategoryLock;
import com.zondamarket.shared.errors.ApiProblemException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryAdminService {
    private static final int MAX_DEPTH = 4;

    private final CategoryAdminStore store;
    private final CategoryLock lock;

    CategoryAdminService(CategoryAdminStore store, CategoryLock lock) {
        this.store = store;
        this.lock = lock;
    }

    @Transactional(readOnly = true)
    public List<CategoryRow> categories() {
        return store.all();
    }

    @Transactional
    public CategoryRow create(CategoryAdminRequest request, UUID actor, UUID trace) {
        if (request.expectedVersion() != null) {
            throw invalid("La categoría nueva todavía no tiene versión.");
        }
        lock.changeTree();
        return save(UUID.randomUUID(), null, request, actor, trace);
    }

    @Transactional
    public CategoryRow edit(UUID id, CategoryAdminRequest request, UUID actor, UUID trace) {
        if (request.expectedVersion() == null) throw invalid("Indicá la versión que querés modificar.");
        lock.changeTree();
        var current = requireActive(id, request.expectedVersion());
        return save(id, current, request, actor, trace);
    }

    private CategoryRow save(UUID id, CategoryRow current, CategoryAdminRequest request, UUID actor, UUID trace) {
        String name = request.name().strip();
        if (!validText(name) || !validSchema(request.attributeSchema())) {
            throw invalid("Ingresá un nombre válido y un esquema con listas de valores de texto únicos.");
        }
        if (store.slugInUse(request.slug(), id)) {
            throw conflict("CATEGORY_SLUG_IN_USE", "Ese slug ya está en uso.");
        }
        if (current == null || !Objects.equals(current.parentId(), request.parentId())) {
            validateMove(id, request.parentId());
        }
        if (current != null && !current.attributeSchema().equals(request.attributeSchema())
                && store.hasIncompatibleSkus(id, request.attributeSchema())) {
            throw conflict("CATEGORY_SKUS_INCOMPATIBLE", "El esquema debe admitir las variantes guardadas.");
        }
        int bps = request.commissionBps().intValueExact();
        if (current != null && Objects.equals(current.parentId(), request.parentId())
                && current.name().equals(name) && current.slug().equals(request.slug())
                && current.attributeSchema().equals(request.attributeSchema()) && current.commissionBps() == bps) {
            return current;
        }
        store.save(id, request.parentId(), name, request.slug(), bps, request.attributeSchema());
        if (current == null || current.commissionBps() != bps) {
            store.closeCommission(id);
            store.recordCommission(id, actor, trace);
        }
        return store.find(id);
    }

    @Transactional
    public CategoryRow archive(UUID id, BigDecimal version) {
        lock.changeTree();
        var current = requireActive(id, version);
        if (current.hasChildren() || current.activeListings() > 0) {
            throw conflict("CATEGORY_IN_USE", "La categoría tiene subcategorías o publicaciones activas.");
        }
        store.archive(id);
        store.closeCommission(id);
        return store.find(id);
    }

    private void validateMove(UUID id, UUID parentId) {
        List<CategoryRow> active = store.all().stream().filter(row -> !row.archived()).toList();
        if (parentId != null) {
            var parent = active.stream().filter(row -> row.id().equals(parentId)).findFirst()
                    .orElseThrow(() -> invalid("Elegí una categoría superior vigente."));
            if (parent.activeListings() > 0) {
                throw conflict("CATEGORY_IN_USE", "La categoría superior tiene publicaciones activas y debe seguir siendo final.");
            }
        }
        Map<UUID, UUID> parents = new HashMap<>();
        active.forEach(row -> parents.put(row.id(), row.parentId()));
        parents.put(id, parentId);
        for (UUID node : parents.keySet()) {
            var visited = new HashSet<UUID>();
            for (UUID ancestor = node; ancestor != null; ancestor = parents.get(ancestor)) {
                if (!visited.add(ancestor)) {
                    throw conflict("CATEGORY_CYCLE", "El movimiento formaría un ciclo en el árbol.");
                }
            }
            if (visited.size() > MAX_DEPTH) {
                throw conflict("CATEGORY_DEPTH", "El árbol admite hasta cuatro niveles.");
            }
        }
    }

    private CategoryRow requireActive(UUID id, BigDecimal version) {
        var current = store.findOptional(id).orElseThrow(() -> new ApiProblemException(
                HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "No encontramos esa categoría."));
        if (current.version() != version.longValueExact()) {
            throw conflict("VERSION_CONFLICT", "La categoría cambió. Cargá los datos actuales antes de guardar.");
        }
        if (current.archived()) {
            throw conflict("CATEGORY_ARCHIVED", "La categoría ya está archivada.");
        }
        return current;
    }

    private static boolean validSchema(Map<String, Object> schema) {
        return schema.entrySet().stream().allMatch(field ->
                validAttribute(field.getKey()) && field.getValue() instanceof List<?> values
                && !values.isEmpty() && values.size() <= 100 && new HashSet<>(values).size() == values.size()
                && values.stream().allMatch(value -> value instanceof String text && validAttribute(text)));
    }

    private static boolean validAttribute(String value) {
        return validText(value) && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
    }

    private static boolean validText(String value) {
        return !value.isBlank() && value.equals(value.strip()) && value.length() <= 120 && value.indexOf(0) < 0;
    }

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", detail);
    }

    private static ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }
}
