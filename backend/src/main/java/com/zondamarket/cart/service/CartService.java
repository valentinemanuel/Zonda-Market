package com.zondamarket.cart.service;

import com.zondamarket.cart.dto.CartDtos.CartResponse;
import com.zondamarket.cart.dto.CartDtos.Item;
import com.zondamarket.cart.dto.CartDtos.SellerGroup;
import com.zondamarket.cart.dto.CartDtos.UnavailableReason;
import com.zondamarket.cart.repository.CartStore;
import com.zondamarket.cart.repository.CartStore.Offer;
import com.zondamarket.shared.errors.ApiProblemException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private final CartStore store;

    CartService(CartStore store) {
        this.store = store;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CartResponse get(UUID buyer) {
        return store.find(buyer).map(cart -> quote(buyer, cart.id(), cart.version()))
                .orElseGet(() -> new CartResponse(0, List.of(), List.of(), ZERO.toPlainString(), "ARS", 0, null));
    }

    @Transactional
    public CartResponse put(UUID buyer, UUID skuId, int quantity, long expectedVersion) {
        var cart = store.lock(buyer);
        requireVersion(cart.version(), expectedVersion);
        requireEditable(cart.id(), skuId);
        var offer = store.offer(skuId).orElseThrow(CartService::missingSku);
        var reason = unavailable(buyer, offer, quantity);
        if (reason != null) {
            throw switch (reason) {
                case SELF_PURCHASE -> new ApiProblemException(HttpStatus.CONFLICT, reason.name(),
                        "No podés agregar una publicación propia.");
                case SKU_UNAVAILABLE, LISTING_UNAVAILABLE -> missingSku();
                case INSUFFICIENT_STOCK -> new ApiProblemException(HttpStatus.CONFLICT, reason.name(),
                        "La cantidad supera el stock disponible.");
            };
        }
        store.put(cart.id(), offer, quantity);
        return quote(buyer, cart.id(), store.advance(cart.id()));
    }

    @Transactional
    public CartResponse remove(UUID buyer, UUID skuId, long expectedVersion) {
        var cart = store.lock(buyer);
        requireVersion(cart.version(), expectedVersion);
        requireEditable(cart.id(), skuId);
        if (!store.remove(cart.id(), skuId)) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "CART_ITEM_NOT_FOUND", "La variante no está en tu carrito.");
        }
        return quote(buyer, cart.id(), store.advance(cart.id()));
    }

    private CartResponse quote(UUID buyer, UUID cartId, long version) {
        var reservations = store.reserved(cartId);
        var reserved = reservations.stream().map(CartStore.ReservedLine::skuId).collect(Collectors.toSet());
        UUID activeOrder = reservations.isEmpty() ? null : reservations.getFirst().orderId();
        Map<UUID, Group> groups = new LinkedHashMap<>();
        List<Item> unavailable = new ArrayList<>();
        long itemCount = 0;
        for (var line : store.lines(cartId)) {
            var offer = line.offer();
            var reason = unavailable(buyer, offer, line.quantity());
            var subtotal = offer.price().multiply(BigDecimal.valueOf(line.quantity()));
            boolean current = offer.visible() && !offer.deleted();
            var item = new Item(offer.skuId(), current ? offer.listingSlug() : null,
                    current ? line.thumbnailUrl() : null,
                    current ? offer.title() : line.referenceTitle(),
                    current ? offer.attributes() : line.referenceAttributes(), line.quantity(), current ? offer.stock() : 0,
                    (current ? offer.price() : line.referencePrice()).toPlainString(), line.referencePrice().toPlainString(),
                    reason == null ? subtotal.toPlainString() : null, reason, reserved.contains(offer.skuId()));
            itemCount += line.quantity();
            if (reason != null) {
                unavailable.add(item);
                continue;
            }
            var group = groups.computeIfAbsent(offer.sellerId(), ignored -> new Group(offer));
            group.items.add(item);
            group.subtotal = group.subtotal.add(subtotal);
        }
        var total = groups.values().stream().map(group -> group.subtotal).reduce(ZERO, BigDecimal::add);
        return new CartResponse(version, groups.values().stream().map(Group::response).toList(),
                unavailable, total.toPlainString(), "ARS", itemCount, activeOrder);
    }

    public static UnavailableReason unavailable(UUID buyer, Offer offer, int quantity) {
        if (!offer.visible()) return UnavailableReason.LISTING_UNAVAILABLE;
        if (offer.deleted()) return UnavailableReason.SKU_UNAVAILABLE;
        if (buyer.equals(offer.sellerAccountId())) return UnavailableReason.SELF_PURCHASE;
        if (offer.stock() < quantity) return UnavailableReason.INSUFFICIENT_STOCK;
        return null;
    }

    private void requireEditable(UUID cart, UUID sku) {
        if (store.reserved(cart).stream().anyMatch(line -> line.skuId().equals(sku))) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "CART_ITEM_RESERVED",
                    "La variante está incluida en una reserva.");
        }
    }

    private static void requireVersion(long current, long expected) {
        if (current != expected) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "CART_VERSION_CONFLICT",
                    "El carrito cambió. Revisá la versión actual antes de guardar.");
        }
    }

    private static ApiProblemException missingSku() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "SKU_NOT_FOUND", "La variante ya no está disponible.");
    }

    private static final class Group {
        private final Offer seller;
        private final List<Item> items = new ArrayList<>();
        private BigDecimal subtotal = ZERO;

        private Group(Offer seller) {
            this.seller = seller;
        }

        private SellerGroup response() {
            return new SellerGroup(seller.sellerId(), seller.sellerSlug(), seller.sellerName(), items, subtotal.toPlainString());
        }
    }
}
