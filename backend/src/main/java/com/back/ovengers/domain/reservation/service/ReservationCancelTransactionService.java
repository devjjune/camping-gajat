package com.back.ovengers.domain.reservation.service;

import com.back.ovengers.domain.chat.service.ChatService;
import com.back.ovengers.domain.payment.entity.Payment;
import com.back.ovengers.domain.payment.entity.PaymentStatus;
import com.back.ovengers.domain.payment.repository.PaymentRepository;
import com.back.ovengers.domain.reservation.dto.ReservationCancelPrepareResult;
import com.back.ovengers.domain.reservation.dto.ReservationCancelResponse;
import com.back.ovengers.domain.reservation.entity.Reservation;
import com.back.ovengers.domain.reservation.entity.ReservationStatus;
import com.back.ovengers.domain.reservation.repository.ReservationRepository;
import com.back.ovengers.global.exception.CustomException;
import com.back.ovengers.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationCancelTransactionService {

    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final ChatService chatService;

    @Transactional
    public ReservationCancelPrepareResult prepareCancel(Long reservationId, Long userId) {

        Reservation reservation = reservationRepository.findByIdWithLock(reservationId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESERVATION_NOT_FOUND));

        validateReservationOwner(reservation, userId);

        // 취소 가능 상태 확인
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new CustomException(ErrorCode.RESERVATION_CANNOT_BE_CANCELLED);
        }

        // DONE 상태 결제 조회
        Payment payment = paymentRepository.findByReservation_IdAndStatus(reservationId, PaymentStatus.DONE)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_NOT_FOUND));

        payment.updateStatus(PaymentStatus.CANCEL_IN_PROGRESS);

        return new ReservationCancelPrepareResult(
                payment.getId(),
                payment.getPaymentKey()
        );
    }

    @Transactional
    public ReservationCancelResponse completeCancel(
            Long reservationId,
            Long paymentId
    ) {

        Reservation reservation = reservationRepository
                .findByIdWithLock(reservationId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.RESERVATION_NOT_FOUND)
                );

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.PAYMENT_NOT_FOUND)
                );

        if (payment.getStatus() != PaymentStatus.CANCEL_IN_PROGRESS) {
            throw new CustomException(ErrorCode.INVALID_PAYMENT_STATUS);
        }

        payment.updateStatus(PaymentStatus.CANCELLED);
        reservation.updateStatus(ReservationStatus.CANCELLED);

        chatService.closeByReservationId(reservationId);

        return ReservationCancelResponse.of(reservation);
    }

    @Transactional
    public void releaseCancel(Long paymentId) {

        paymentRepository.findById(paymentId)
                .ifPresent(payment -> {

                    if (payment.getStatus()
                            == PaymentStatus.CANCEL_IN_PROGRESS) {

                        payment.updateStatus(PaymentStatus.DONE);
                    }
                });
    }

    // 예약 소유자 검증
    private void validateReservationOwner(Reservation reservation, Long userId) {
        if (!reservation.getUser().getId().equals(userId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }
}
