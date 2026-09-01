package com.back.ovengers.domain.payment.entity;

public enum PaymentStatus {
    READY,
    DONE,
    IN_PROGRESS,
    CANCEL_IN_PROGRESS, // Toss 취소 요청을 보내기 위해 DB에서 취소 권한을 선점한 상태
    CANCELLED,
    FAILED
}
