package com.back.ovengers.domain.payment.service;

import com.back.ovengers.domain.camping.entity.Camping;
import com.back.ovengers.domain.notification.entity.NotificationType;
import com.back.ovengers.domain.notification.service.NotificationService;
import com.back.ovengers.domain.payment.client.TossPaymentClient;
import com.back.ovengers.domain.payment.dto.PaymentConfirmRequest;
import com.back.ovengers.domain.payment.dto.PaymentConfirmResponse;
import com.back.ovengers.domain.payment.dto.PaymentRequest;
import com.back.ovengers.domain.payment.dto.PaymentResponse;
import com.back.ovengers.domain.payment.dto.TossConfirmResponse;
import com.back.ovengers.domain.payment.entity.Payment;
import com.back.ovengers.domain.payment.entity.PaymentStatus;
import com.back.ovengers.domain.payment.event.PaymentCompletedEvent;
import com.back.ovengers.domain.payment.repository.PaymentRepository;
import com.back.ovengers.domain.reservation.entity.Reservation;
import com.back.ovengers.domain.reservation.entity.ReservationStatus;
import com.back.ovengers.domain.reservation.repository.ReservationRepository;
import com.back.ovengers.global.exception.CustomException;
import com.back.ovengers.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final TossPaymentClient tossPaymentClient;
    private final PaymentConfirmTransactionService paymentConfirmTransactionService;

    @Transactional
    public PaymentResponse create(Long userId, PaymentRequest request) {

        Reservation reservation = reservationRepository.findById(request.reservationId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESERVATION_NOT_FOUND));

        if (!reservation.getUser().getId().equals(userId)) {
            throw new CustomException(ErrorCode.RESERVATION_FORBIDDEN);
        }

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new CustomException(ErrorCode.INVALID_RESERVATION_STATUS);
        }

        boolean alreadyPaid = paymentRepository
                .findAllByReservation_Id(request.reservationId())
                .stream()
                .anyMatch(payment -> payment.getStatus() == PaymentStatus.DONE);

        if (alreadyPaid) {
            throw new CustomException(ErrorCode.ALREADY_PAID);
        }

        String orderId = generateOrderId();

        Payment payment = Payment.builder()
                .reservation(reservation)
                .orderId(orderId)
                .paidPrice(reservation.getRsvPrice())
                .status(PaymentStatus.READY)
                .build();

        return PaymentResponse.of(
                paymentRepository.save(payment),
                reservation
        );
    }

    /**
     * 결제 승인 오케스트레이션
     *
     * 1. 검증 + 결제 선점 (짧은 TX)
     * 2. Toss 승인 API 호출 (TX 없음)
     * 3. 결제/예약 상태 최종 반영 (짧은 TX)
     */
    public PaymentConfirmResponse confirm(PaymentConfirmRequest request) {

        Long paymentId =
                paymentConfirmTransactionService.preConfirm(request);

        TossConfirmResponse tossResponse;

        try {
            tossResponse = tossPaymentClient.confirm(
                    request.paymentKey(),
                    request.orderId(),
                    request.amount()
            );

        } catch (Exception e) {

            paymentConfirmTransactionService.releaseConfirm(paymentId);

            throw new CustomException(
                    ErrorCode.PAYMENT_CONFIRM_FAILED
            );
        }

        return paymentConfirmTransactionService.postConfirm(
                paymentId,
                request.paymentKey(),
                tossResponse
        );
    }

    private String generateOrderId() {

        String prefix = RandomStringUtils
                .randomAlphabetic(3)
                .toUpperCase();

        return prefix
                + "-"
                + LocalDate.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-"
                + UUID.randomUUID()
                .toString()
                .substring(0, 8)
                .toUpperCase();
    }
}