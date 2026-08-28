package com.back.ovengers.domain.payment.service;

import com.back.ovengers.domain.camping.entity.Camping;
import com.back.ovengers.domain.notification.entity.NotificationType;
import com.back.ovengers.domain.notification.service.NotificationService;
import com.back.ovengers.domain.payment.dto.PaymentConfirmRequest;
import com.back.ovengers.domain.payment.dto.PaymentConfirmResponse;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentConfirmTransactionService {

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationService notificationService;

    /**
     * 1단계: 검증 + 결제 선점
     *
     * 같은 예약의 결제 요청을 직렬화하기 위해 락을 획득하고,
     * 검증을 통과한 결제를 IN_PROGRESS 상태로 변경한다.
     *
     * 메서드 종료 시 커밋되면서 락이 해제된다.
     */
    @Transactional
    public Long preConfirm(PaymentConfirmRequest request) {

        Payment payment = paymentRepository.findByOrderId(request.orderId())
                .orElseThrow(() ->
                        new CustomException(ErrorCode.PAYMENT_NOT_FOUND)
                );

        Reservation reservation = reservationRepository
                .findByIdWithLock(payment.getReservation().getId())
                .orElseThrow(() ->
                        new CustomException(ErrorCode.RESERVATION_NOT_FOUND)
                );

        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            throw new CustomException(ErrorCode.ALREADY_PAID);
        }

        /*
         * 같은 예약에 생성된 모든 결제를 락으로 조회하여
         * 최신 상태를 기준으로 중복 결제를 방지한다.
         */
        List<Payment> lockedPayments =
                paymentRepository.findAllByReservationIdWithLock(
                        reservation.getId()
                );

        Payment lockedPayment = lockedPayments.stream()
                .filter(p -> p.getId().equals(payment.getId()))
                .findFirst()
                .orElseThrow(() ->
                        new CustomException(ErrorCode.PAYMENT_NOT_FOUND)
                );

        validatePaymentStatus(
                lockedPayment,
                lockedPayments
        );

        if (!lockedPayment.getPaidPrice().equals(request.amount())) {
            throw new CustomException(ErrorCode.AMOUNT_MISMATCH);
        }

        /*
         * Toss API 호출 전에 결제 처리 권한을 선점한다.
         *
         * 영속 상태의 Entity이므로
         * 트랜잭션 커밋 시 dirty checking으로 반영된다.
         */
        lockedPayment.updateStatus(PaymentStatus.IN_PROGRESS);

        return lockedPayment.getId();
    }

    /**
     * 3단계: Toss 승인 성공 후 최종 상태 반영
     */
    @Transactional
    public PaymentConfirmResponse postConfirm(
            Long paymentId,
            String paymentKey,
            TossConfirmResponse tossResponse
    ) {

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.PAYMENT_NOT_FOUND)
                );

        /*
         * 예약 취소 등 같은 Reservation을 변경하는 작업과
         * 충돌하지 않도록 다시 락을 획득한다.
         */
        Reservation reservation = reservationRepository
                .findByIdWithLock(payment.getReservation().getId())
                .orElseThrow(() ->
                        new CustomException(ErrorCode.RESERVATION_NOT_FOUND)
                );

        payment.confirm(paymentKey);
        reservation.updateStatus(ReservationStatus.CONFIRMED);

        cancelOtherReadyPayments(
                reservation.getId(),
                payment.getId()
        );

        publishPaymentCompletedEvent(reservation);
        sendPaymentCompletedNotification(reservation);

        return PaymentConfirmResponse.of(
                payment,
                tossResponse.method(),
                tossResponse.approvedAt()
        );
    }

    /**
     * Toss 승인 실패 시 선점 상태 복구
     */
    @Transactional
    public void releaseConfirm(Long paymentId) {

        paymentRepository.findById(paymentId)
                .ifPresent(payment -> {

                    if (payment.getStatus() == PaymentStatus.IN_PROGRESS) {
                        payment.updateStatus(PaymentStatus.READY);
                    }
                });
    }

    private void validatePaymentStatus(
            Payment currentPayment,
            List<Payment> lockedPayments
    ) {

        if (currentPayment.getStatus() == PaymentStatus.DONE
                || currentPayment.getStatus() == PaymentStatus.IN_PROGRESS) {

            throw new CustomException(ErrorCode.ALREADY_PAID);
        }

        boolean otherActive = lockedPayments.stream()
                .filter(payment ->
                        !payment.getId().equals(currentPayment.getId())
                )
                .anyMatch(payment ->
                        payment.getStatus() == PaymentStatus.IN_PROGRESS
                                || payment.getStatus() == PaymentStatus.DONE
                );

        if (otherActive) {
            throw new CustomException(ErrorCode.ALREADY_PAID);
        }

        if (currentPayment.getStatus() != PaymentStatus.READY) {
            throw new CustomException(
                    ErrorCode.INVALID_PAYMENT_STATUS
            );
        }
    }

    private void cancelOtherReadyPayments(
            Long reservationId,
            Long confirmedPaymentId
    ) {

        paymentRepository.findAllByReservation_Id(reservationId)
                .stream()
                .filter(payment ->
                        !payment.getId().equals(confirmedPaymentId)
                )
                .filter(payment ->
                        payment.getStatus() == PaymentStatus.READY
                )
                .forEach(payment ->
                        payment.updateStatus(PaymentStatus.CANCELLED)
                );
    }

    private void publishPaymentCompletedEvent(
            Reservation reservation
    ) {

        Camping camping =
                reservation.getSite().getCamping();

        eventPublisher.publishEvent(
                new PaymentCompletedEvent(
                        reservation.getId(),
                        reservation.getUser().getId(),
                        camping.getHost().getId(),
                        camping.getName()
                )
        );
    }

    private void sendPaymentCompletedNotification(
            Reservation reservation
    ) {

        notificationService.send(
                reservation.getUser(),
                NotificationType.PAYMENT_DONE,
                "결제가 완료되었습니다."
        );
    }
}