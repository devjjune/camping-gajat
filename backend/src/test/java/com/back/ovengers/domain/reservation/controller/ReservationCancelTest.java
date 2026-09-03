package com.back.ovengers.domain.reservation.controller;

import com.back.ovengers.domain.camping.entity.Camping;
import com.back.ovengers.domain.camping.entity.CampingStatus;
import com.back.ovengers.domain.camping.repository.CampingRepository;
import com.back.ovengers.domain.chat.entity.ChatRoom;
import com.back.ovengers.domain.chat.enums.ChatRoomStatus;
import com.back.ovengers.domain.chat.enums.ChatRoomType;
import com.back.ovengers.domain.chat.repository.ChatRoomRepository;
import com.back.ovengers.domain.payment.client.TossPaymentClient;
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
import com.back.ovengers.global.security.JwtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReservationCancelTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SiteRepository siteRepository;
    @Autowired private CampingRepository campingRepository;
    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private JwtProvider jwtProvider;

    @MockitoBean
    private TossPaymentClient tossPaymentClient;

    private User user;
    private Reservation reservation;
    private String accessToken;
    private Payment payment;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        reservationRepository.deleteAll();
        siteRepository.deleteAll();
        campingRepository.deleteAll();
        userRepository.deleteAll();
        chatRoomRepository.deleteAll();

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

        Camping camping = campingRepository.save(Camping.builder()
                .host(host)
                .name("테스트 캠핑장")
                .region("서울")
                .city("강남구")
                .address("서울시 강남구 테스트로 123")
                .status(CampingStatus.APPROVED)
                .build());

        Site site = siteRepository.save(Site.builder()
                .camping(camping)
                .name("A구역")
                .baseCapacity(2)
                .maxCapacity(4)
                .totalAmount(5)
                .price(50000)
                .build());

        reservation = reservationRepository.save(Reservation.builder()
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
                .build());

        payment = paymentRepository.save(Payment.builder()
                .reservation(reservation)
                .orderId("ORD-" + UUID.randomUUID())
                .paymentKey("test_payment_key")
                .paidPrice(100000)
                .status(PaymentStatus.DONE)
                .build());

        chatRoomRepository.save(
                ChatRoom.builder()
                        .reservationId(reservation.getId())
                        .name(camping.getName() + " 채팅방")
                        .type(ChatRoomType.DIRECT)
                        .status(ChatRoomStatus.ACTIVE)
                        .build());

        accessToken = jwtProvider.createRefreshToken(user.getId(), user.getRole().name());

        willDoNothing().given(tossPaymentClient).cancel(anyString(), anyString());
    }

    @AfterEach
    void tearDown() {
        paymentRepository.deleteAll();
        reservationRepository.deleteAll();
        siteRepository.deleteAll();
        campingRepository.deleteAll();
        userRepository.deleteAll();
        chatRoomRepository.deleteAll();
    }

    @Test
    @DisplayName("예약 취소 성공")
    void CancelReservationSuccess() throws Exception {

        mockMvc.perform(patch("/api/reservations/{id}/cancel", reservation.getId())
                        .cookie(new MockCookie("accessToken", accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("예약이 취소되었습니다."))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.rsvNum").value(reservation.getRsvNum()));

        Reservation cancelledReservation =
                reservationRepository.findById(reservation.getId())
                        .orElseThrow();

        assertThat(cancelledReservation.getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);

        Payment result = paymentRepository.findById(payment.getId())
                .orElseThrow();

        assertThat(result.getStatus())
                .isEqualTo(PaymentStatus.CANCELLED);

        assertThat(chatRoomRepository.findByReservationIdAndTypeAndStatus(
                reservation.getId(),
                ChatRoomType.DIRECT,
                ChatRoomStatus.CLOSED
        )).isPresent();
    }

    @Test
    @DisplayName("예약 취소 실패 - 예약 없음")
    void CancelReservationFailNotFound() throws Exception {

        mockMvc.perform(patch("/api/reservations/{id}/cancel", 999999L)
                        .cookie(new MockCookie("accessToken", accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value("예약을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("예약 취소 실패 - 본인 예약 아님")
    void CancelReservationFailForbidden() throws Exception {

        User otherUser = userRepository.save(
                UserFixture.user()
                        .email("other" + System.nanoTime() + "@test.com")
                        .nickname("다른유저" + System.nanoTime())
                        .build()
        );

        String otherToken = jwtProvider.createRefreshToken(otherUser.getId(), otherUser.getRole().name());

        mockMvc.perform(patch("/api/reservations/{id}/cancel", reservation.getId())
                        .cookie(new MockCookie("accessToken", otherToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").value("접근 권한이 없습니다."));
    }

    @Test
    @DisplayName("예약 취소 실패 - 취소 불가 상태 (CANCELLED)")
    void CancelReservationFailAlreadyCancelled() throws Exception {

        reservation.updateStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);

        mockMvc.perform(patch("/api/reservations/{id}/cancel", reservation.getId())
                        .cookie(new MockCookie("accessToken", accessToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data").value("취소할 수 없는 예약 상태입니다."));
    }

    @Test
    @DisplayName("예약 취소 실패 - 취소 불가 상태 (COMPLETED)")
    void CancelReservationFailCompleted() throws Exception {

        reservation.updateStatus(ReservationStatus.COMPLETED);
        reservationRepository.save(reservation);

        mockMvc.perform(patch("/api/reservations/{id}/cancel", reservation.getId())
                        .cookie(new MockCookie("accessToken", accessToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data").value("취소할 수 없는 예약 상태입니다."));
    }

    @Test
    @DisplayName("예약 취소 실패 - 취소 불가 상태 (CANCEL_IN_PROGRESS)")
    void cancelReservationFail_cancelInProgress() throws Exception {

        Payment payment = paymentRepository
                .findByReservation_IdAndStatus(
                        reservation.getId(),
                        PaymentStatus.DONE
                )
                .orElseThrow();

        payment.updateStatus(PaymentStatus.CANCEL_IN_PROGRESS);
        paymentRepository.save(payment);

        mockMvc.perform(patch("/api/reservations/{id}/cancel", reservation.getId())
                        .cookie(new MockCookie("accessToken", accessToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data")
                        .value("결제 취소가 이미 진행 중입니다."));
    }

    @Test
    @DisplayName("예약 취소 실패 - Toss 취소 실패 시 결제 상태를 DONE으로 복구")
    void cancelReservationFail_tossCancelFailed() throws Exception {

        willThrow(new RuntimeException("Toss cancel failed"))
                .given(tossPaymentClient)
                .cancel(anyString(), anyString());

        mockMvc.perform(patch("/api/reservations/{id}/cancel", reservation.getId())
                        .cookie(new MockCookie("accessToken", accessToken)))
                .andExpect(status().isInternalServerError());

        Payment result = paymentRepository.findById(payment.getId())
                .orElseThrow();

        assertThat(result.getStatus())
                .isEqualTo(PaymentStatus.DONE);

        Reservation resultReservation =
                reservationRepository.findById(reservation.getId())
                        .orElseThrow();

        assertThat(resultReservation.getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }
}
