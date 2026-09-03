package com.back.ovengers.domain.reservation.service;

import com.back.ovengers.domain.camping.entity.Camping;
import com.back.ovengers.domain.camping.entity.CampingStatus;
import com.back.ovengers.domain.camping.repository.CampingRepository;
import com.back.ovengers.domain.chat.entity.ChatRoom;
import com.back.ovengers.domain.chat.enums.ChatRoomStatus;
import com.back.ovengers.domain.chat.enums.ChatRoomType;
import com.back.ovengers.domain.chat.repository.ChatRoomRepository;
import com.back.ovengers.domain.payment.entity.Payment;
import com.back.ovengers.domain.payment.entity.PaymentStatus;
import com.back.ovengers.domain.payment.repository.PaymentRepository;
import com.back.ovengers.domain.reservation.entity.Reservation;
import com.back.ovengers.domain.reservation.entity.ReservationStatus;
import com.back.ovengers.domain.reservation.repository.ReservationRepository;
import com.back.ovengers.domain.site.entity.Site;
import com.back.ovengers.domain.site.repository.SiteRepository;
import com.back.ovengers.domain.user.entity.User;
import com.back.ovengers.domain.user.repository.UserRepository;
import com.back.ovengers.fixture.UserFixture;
import com.back.ovengers.global.exception.CustomException;
import com.back.ovengers.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReservationCancelTransactionServiceTest {

    @Autowired private ReservationCancelTransactionService reservationCancelTransactionService;

    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SiteRepository siteRepository;
    @Autowired private CampingRepository campingRepository;
    @Autowired private ChatRoomRepository chatRoomRepository;

    private User user;
    private Reservation reservation;
    private Payment payment;

    @BeforeEach
    void setUp() {

        user = userRepository.save(
                UserFixture.user()
                        .email("user" + System.nanoTime() + "@test.com")
                        .nickname("유저" + System.nanoTime())
                        .build()
        );

        User host = userRepository.save(
                UserFixture.host()
                        .email("host" + System.nanoTime() + "@test.com")
                        .nickname("호스트" + System.nanoTime())
                        .build()
        );

        Camping camping = campingRepository.save(
                Camping.builder()
                        .host(host)
                        .name("테스트 캠핑장")
                        .region("서울")
                        .city("강남구")
                        .address("서울시 강남구 테스트로 123")
                        .status(CampingStatus.APPROVED)
                        .build()
        );

        Site site = siteRepository.save(
                Site.builder()
                        .camping(camping)
                        .name("A구역")
                        .baseCapacity(2)
                        .maxCapacity(4)
                        .totalAmount(5)
                        .price(50000)
                        .build()
        );

        reservation = reservationRepository.save(
                Reservation.builder()
                        .user(user)
                        .site(site)
                        .rsvNum("RSV-" + UUID.randomUUID())
                        .rsvName("홍길동")
                        .rsvPhone("010-1234-5678")
                        .checkIn(LocalDate.now().plusDays(3))
                        .checkOut(LocalDate.now().plusDays(5))
                        .guestCount(2)
                        .rsvPrice(100000)
                        .status(ReservationStatus.CONFIRMED)
                        .build()
        );

        payment = paymentRepository.save(
                Payment.builder()
                        .reservation(reservation)
                        .orderId("ORD-" + UUID.randomUUID())
                        .paymentKey("test_payment_key")
                        .paidPrice(100000)
                        .status(PaymentStatus.DONE)
                        .build()
        );

        chatRoomRepository.save(
                ChatRoom.builder()
                        .reservationId(reservation.getId())
                        .name(camping.getName() + " 채팅방")
                        .type(ChatRoomType.DIRECT)
                        .status(ChatRoomStatus.ACTIVE)
                        .build()
        );
    }

    @Test
    @DisplayName("취소 준비 성공 - DONE 결제를 CANCEL_IN_PROGRESS로 변경")
    void prepareCancel_success() {

        // when
        reservationCancelTransactionService.prepareCancel(
                reservation.getId(),
                user.getId()
        );

        // then
        Payment updatedPayment = paymentRepository.findById(payment.getId())
                .orElseThrow();

        assertThat(updatedPayment.getStatus())
                .isEqualTo(PaymentStatus.CANCEL_IN_PROGRESS);
    }

    @Test
    @DisplayName("취소 준비 실패 - 이미 결제 취소 진행 중")
    void prepareCancel_fail_cancelInProgress() {

        // given
        payment.updateStatus(PaymentStatus.CANCEL_IN_PROGRESS);
        paymentRepository.save(payment);

        // when & then
        assertThatThrownBy(() ->
                reservationCancelTransactionService.prepareCancel(
                        reservation.getId(),
                        user.getId()
                )
        )
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException exception = (CustomException) e;

                    assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.PAYMENT_CANCEL_IN_PROGRESS);
                });
    }

    @Test
    @DisplayName("취소 완료 성공 - CANCEL_IN_PROGRESS 결제를 CANCELLED로 변경")
    void completeCancel_success() {

        // given
        payment.updateStatus(PaymentStatus.CANCEL_IN_PROGRESS);
        paymentRepository.save(payment);

        // when
        reservationCancelTransactionService.completeCancel(
                reservation.getId(),
                payment.getId()
        );

        // then
        Payment updatedPayment = paymentRepository.findById(payment.getId())
                .orElseThrow();

        Reservation updatedReservation =
                reservationRepository.findById(reservation.getId())
                        .orElseThrow();

        assertThat(updatedPayment.getStatus())
                .isEqualTo(PaymentStatus.CANCELLED);

        assertThat(updatedReservation.getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    @DisplayName("취소 완료 실패 - 결제가 CANCEL_IN_PROGRESS 상태가 아님")
    void completeCancel_fail_invalidPaymentStatus() {

        // payment는 setUp에서 DONE 상태

        // when & then
        assertThatThrownBy(() ->
                reservationCancelTransactionService.completeCancel(
                        reservation.getId(),
                        payment.getId()
                )
        )
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException exception = (CustomException) e;

                    assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_PAYMENT_STATUS);
                });
    }

    @Test
    @DisplayName("취소 선점 해제 성공 - CANCEL_IN_PROGRESS 결제를 DONE으로 복구")
    void releaseCancel_success() {

        // given
        payment.updateStatus(PaymentStatus.CANCEL_IN_PROGRESS);
        paymentRepository.save(payment);

        // when
        reservationCancelTransactionService.releaseCancel(
                payment.getId()
        );

        // then
        Payment updatedPayment = paymentRepository.findById(payment.getId())
                .orElseThrow();

        assertThat(updatedPayment.getStatus())
                .isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("취소 선점 해제 - CANCEL_IN_PROGRESS가 아니면 상태를 변경하지 않음")
    void releaseCancel_doesNotChangeOtherStatus() {

        // given
        payment.updateStatus(PaymentStatus.CANCELLED);
        paymentRepository.save(payment);

        // when
        reservationCancelTransactionService.releaseCancel(
                payment.getId()
        );

        // then
        Payment updatedPayment = paymentRepository.findById(payment.getId())
                .orElseThrow();

        assertThat(updatedPayment.getStatus())
                .isEqualTo(PaymentStatus.CANCELLED);
    }
}