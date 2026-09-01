package com.back.ovengers.domain.reservation.dto;

public record ReservationCancelPrepareResult(
        Long paymentId,
        String paymentKey
) {
}
