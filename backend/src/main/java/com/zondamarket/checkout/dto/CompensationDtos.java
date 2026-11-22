package com.zondamarket.checkout.dto;

import java.util.UUID;

public final class CompensationDtos {
    private CompensationDtos() {}

    public enum Reason { TARDIO, DUPLICADO }

    public enum Status { PENDIENTE, CONFIRMADA, EN_REVISION }

    public record Refund(UUID id, Reason reason, Status status, String amount) {}
}
