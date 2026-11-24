package com.zondamarket.catalog.service;

import com.zondamarket.catalog.dto.DiscoveryDtos.*;
import com.zondamarket.catalog.repository.DiscoveryStore;
import com.zondamarket.shared.errors.ApiProblemException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiscoveryService {
    private final DiscoveryStore store;

    DiscoveryService(DiscoveryStore store) {
        this.store = store;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ListingPage search(String q, UUID category, BigDecimal min, BigDecimal max, String seller,
                              List<String> attr, Boolean inStock, ConditionFilter condition, int page, int size, Sort sort) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw invalid("El precio mínimo debe ser menor o igual al máximo.");
        }
        if (attr.size() > 16 || q.indexOf('\0') >= 0) {
            throw invalid("Revisá los términos y atributos de la búsqueda.");
        }
        var attributes = new LinkedHashMap<String, String>();
        for (String entry : attr) {
            String[] pair = entry.split(":", 2);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()
                    || pair[0].length() > 80 || pair[1].length() > 160 || entry.indexOf('\0') >= 0
                    || attributes.putIfAbsent(pair[0], pair[1]) != null) {
                throw invalid("Usá un valor por atributo con el formato nombre:valor.");
            }
        }
        return store.search(new Search(q.strip(), category, min, max, seller, attributes, inStock, condition, page, size, sort));
    }

    private static ApiProblemException invalid(String message) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }
}
