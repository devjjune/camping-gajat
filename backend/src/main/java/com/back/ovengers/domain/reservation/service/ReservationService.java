package com.back.ovengers.domain.reservation.service;

import com.back.ovengers.domain.camping.entity.CampingStatus;
import com.back.ovengers.domain.chat.service.ChatService;
import com.back.ovengers.domain.payment.client.TossPaymentClient;
import com.back.ovengers.domain.payment.dto.PaymentSummaryResponse;
import com.back.ovengers.domain.payment.entity.Payment;
import com.back.ovengers.domain.payment.entity.PaymentStatus;
import com.back.ovengers.domain.payment.repository.PaymentRepository;
import com.back.ovengers.domain.reservation.dto.*;
import com.back.ovengers.domain.reservation.dto.*;
import com.back.ovengers.domain.reservation.entity.Reservation;
import com.back.ovengers.domain.reservation.entity.ReservationStatus;
import com.back.ovengers.domain.reservation.repository.ReservationRepository;
import com.back.ovengers.domain.site.entity.Site;
import com.back.ovengers.domain.site.repository.SiteRepository;
import com.back.ovengers.domain.timedeal.entity.TimeDeal;
import com.back.ovengers.domain.timedeal.entity.TimeDealStatus;
import com.back.ovengers.domain.timedeal.repository.TimeDealRepository;
import com.back.ovengers.domain.user.entity.User;
import com.back.ovengers.domain.user.repository.UserRepository;
import com.back.ovengers.global.exception.CustomException;
import com.back.ovengers.global.exception.ErrorCode;
import com.back.ovengers.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final SiteRepository siteRepository;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final TossPaymentClient tossPaymentClient;
    private final TimeDealRepository timeDealRepository;
    private final ChatService chatService;

    // 일반 예약 생성
    public ReservationResponse create(Long userId, ReservationRequest request) {

        User user = getUser(userId);

        validateReservationDate(request.checkIn(), request.checkOut());

        Site site = siteRepository.findByIdWithLock(request.siteId())
                .orElseThrow(() -> new CustomException(ErrorCode.SITE_NOT_FOUND));

        validateSite(site, request.guestCount());

        validateReservationStock(site, request.checkIn(), request.checkOut());

        Reservation reservation = buildReservation(user, site, request);

        return ReservationResponse.of(reservationRepository.save(reservation));
    }

    // 예약 상세 조회
    @Transactional(readOnly = true)
    public ReservationDetailResponse getReservation(Long reservationId, Long userId) {

        Reservation reservation = getOwnedReservation(reservationId, userId);

        return ReservationDetailResponse.of(reservation);
    }

    // 사용자 예약 목록 조회 - 페이지당 10개
    @Transactional(readOnly = true)
    public PageResponse<ReservationResponse> getMyReservations(Long userId, int page) {

        getUser(userId);

        Pageable pageable = PageRequest.of(page, 10);

        Page<ReservationResponse> responsePage = reservationRepository
                .findByUserIdWithDetails(userId, pageable)
                .map(ReservationResponse::of);

        return PageResponse.from(responsePage);
    }

    // 결제 요약 조회
    @Transactional(readOnly = true)
    public PaymentSummaryResponse getSummary(Long reservationId, Long userId) {

        Reservation reservation = getOwnedReservation(reservationId, userId);

        return PaymentSummaryResponse.of(reservation);
    }

    // 호스트 예약 목록 조회
    @Transactional(readOnly = true)
    public Page<HostReservationResponse> getHostReservations(Long hostId, Pageable pageable) {

        return reservationRepository.findHostReservations(hostId, pageable);
    }

    // 예약 취소
    public ReservationCancelResponse cancelReservation(Long reservationId, Long userId) {

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

        // 토스 결제 취소 API 호출 (트랜잭션 밖에서 실행)
        cancelPaymentOutsideTransaction(payment);

        // 예약/결제 상태 변경 (트랜잭션 안에서 처리)
        payment.updateStatus(PaymentStatus.CANCELLED);
        reservation.updateStatus(ReservationStatus.CANCELLED);

        chatService.closeByReservationId(reservationId);

        return ReservationCancelResponse.of(reservation);
    }

    // 트랜잭션 밖에서 토스 API 호출
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void cancelPaymentOutsideTransaction(Payment payment) {
        try {
            tossPaymentClient.cancel(payment.getPaymentKey(), "사용자 예약 취소");
        } catch (Exception e) {
            log.error("토스 결제 취소 실패 - paymentKey: {}, error: {}",
                    payment.getPaymentKey(), e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 타임딜 예약 생성
     *
     * purchaseAtomically()와 reservation 저장은 같은 트랜잭션에서 처리한다.
     *
     * 예약 저장 중 예외 발생
     * → 트랜잭션 롤백
     * → soldCount 증가도 함께 롤백
     */
    public ReservationResponse createTimeDealReservation(
            Long userId,
            Long timeDealId,
            TimeDealReservationRequest request
    ) {
        User user = getUser(userId);

        // 타임딜 조회
        TimeDeal timeDeal = timeDealRepository.findActiveById(timeDealId)
                .orElseThrow(() -> new CustomException(ErrorCode.TIME_DEAL_NOT_FOUND));

        validateTimeDealStatus(timeDeal);

        Site site = timeDeal.getSite();

        validateSite(site, request.guestCount());

        /*
         * WHERE status = ACTIVE
         * AND soldCount + 1 <= quantity
         *
         * 조건을 DB에서 검사하면서 soldCount를 원자적으로 증가시킨다.
         */
        int affectedRows = timeDealRepository.purchaseAtomically(
                timeDealId,
                1,
                TimeDealStatus.ACTIVE
        );

        if (affectedRows == 0) {
            handleTimeDealPurchaseFailure(timeDealId);
        }

        Reservation reservation = buildTimeDealReservation(
                user,
                site,
                timeDeal,
                request
        );

        ReservationResponse response = ReservationResponse.of(
                reservationRepository.save(reservation)
        );

        /*
         * 마지막 재고가 소진되었으면 SOLD_OUT으로 전환
         */
        timeDealRepository.markSoldOutIfExhausted(
                timeDealId,
                TimeDealStatus.SOLD_OUT,
                TimeDealStatus.ACTIVE
        );

        return response;
    }

    // =========================================================
    // 조회
    // =========================================================

    private User getUser(Long userId) {

        return userRepository.findById(userId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.USER_NOT_FOUND)
                );
    }


    // 본인 소유 예약 조회
    private Reservation getOwnedReservation(
            Long reservationId,
            Long userId
    ) {

        Reservation reservation = reservationRepository
                .findByIdWithSiteAndCamping(reservationId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.RESERVATION_NOT_FOUND)
                );

        validateReservationOwner(reservation, userId);

        return reservation;
    }

    // =========================================================
    // 검증
    // =========================================================

    // 예약 날짜 검증
    private void validateReservationDate(LocalDate checkIn, LocalDate checkOut) {

        if (!checkIn.isBefore(checkOut)) {
            throw new CustomException(ErrorCode.INVALID_RESERVATION_DATE);
        }

        if (checkIn.isBefore(LocalDate.now())) {
            throw new CustomException(ErrorCode.INVALID_RESERVATION_DATE);
        }
    }

    // Site 검증 (일반 예약, 타임딜 예약)
    private void validateSite(Site site, int guestCount) {
        if (site.isDeleted()) {
            throw new CustomException(ErrorCode.SITE_NOT_FOUND);
        }

        if (site.getCamping().getStatus() != CampingStatus.APPROVED) {
            throw new CustomException(ErrorCode.CAMPING_NOT_AVAILABLE);
        }

        if (guestCount > site.getMaxCapacity()) {
            throw new CustomException(ErrorCode.GUEST_COUNT_EXCEEDED);
        }
    }

    //일반 예약 재고 검증
    private void validateReservationStock(
            Site site,
            LocalDate checkIn,
            LocalDate checkOut
    ) {

        // 겹치는 예약 조회, 조회 과정에서 비관적 락 적용
        List<Reservation> overlapping =
                reservationRepository.findOverlappingReservationsWithLock(
                        site.getId(),
                        checkIn,
                        checkOut,
                        ReservationStatus.CANCELLED
                );
        // 겹치는 예약 개수 확인, 총 재고와 비교
        if (overlapping.size() >= site.getTotalAmount()) {
            throw new CustomException(
                    ErrorCode.SITE_NOT_AVAILABLE
            );
        }
    }

    // 예약 소유자 검증
    private void validateReservationOwner(Reservation reservation, Long userId) {
        if (!reservation.getUser().getId().equals(userId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }

    // 타임딜 상태 검증
    private void validateTimeDealStatus(TimeDeal timeDeal) {

        if (timeDeal.getStatus() == TimeDealStatus.SOLD_OUT) {
            throw new CustomException(ErrorCode.TIME_DEAL_SOLD_OUT);
        }

        if (timeDeal.getStatus() != TimeDealStatus.ACTIVE) {
            throw new CustomException(ErrorCode.TIME_DEAL_NOT_ACTIVE);
        }
    }

    // 타임딜 원자적 구매 실패 원인 확인
    private void handleTimeDealPurchaseFailure(Long timeDealId) {

        /*
         * findActiveById()가 아니라 findById()를 사용한다.
         *
         * SOLD_OUT / INACTIVE 상태도 조회해야
         * 구매 실패 원인을 구분할 수 있기 때문이다.
         */
        TimeDeal fresh = timeDealRepository.findById(timeDealId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.TIME_DEAL_NOT_FOUND)
                );

        if (fresh.getStatus() == TimeDealStatus.SOLD_OUT) {
            throw new CustomException(
                    ErrorCode.TIME_DEAL_SOLD_OUT
            );
        }

        if (fresh.getStatus() != TimeDealStatus.ACTIVE) {
            throw new CustomException(
                    ErrorCode.TIME_DEAL_NOT_ACTIVE
            );
        }

        /*
         * ACTIVE 상태인데 update에 실패했다면
         * 동시 요청으로 마지막 재고가 먼저 판매된 경우
         */
        throw new CustomException(
                ErrorCode.TIME_DEAL_STOCK_EXCEEDED
        );
    }

    // =========================================================
    // Reservation 생성
    // =========================================================

    // 일반 예약 Entity 생성
    private Reservation buildReservation(
            User user,
            Site site,
            ReservationRequest request
    ) {

        int reservationPrice = calculatePrice(
                site.getPrice(),
                request.checkIn(),
                request.checkOut()
        );

        return Reservation.builder()
                .user(user)
                .site(site)
                .rsvNum(generateRsvNum())
                .rsvName(request.rsvName())
                .rsvPhone(request.rsvPhone())
                .checkIn(request.checkIn())
                .checkOut(request.checkOut())
                .guestCount(request.guestCount())
                .rsvPrice(reservationPrice)
                .request(request.request())
                .status(ReservationStatus.PENDING)
                .build();
    }


    // 타임딜 예약 Entity 생성
    private Reservation buildTimeDealReservation(
            User user,
            Site site,
            TimeDeal timeDeal,
            TimeDealReservationRequest request
    ) {

        int reservationPrice = calculatePrice(
                timeDeal.getDealPrice(),
                timeDeal.getCheckIn(),
                timeDeal.getCheckOut()
        );

        return Reservation.builder()
                .user(user)
                .site(site)
                .timeDeal(timeDeal)
                .rsvNum(generateRsvNum())
                .rsvName(request.rsvName())
                .rsvPhone(request.rsvPhone())
                .checkIn(timeDeal.getCheckIn())
                .checkOut(timeDeal.getCheckOut())
                .guestCount(request.guestCount())
                .rsvPrice(reservationPrice)
                .request(request.request())
                .status(ReservationStatus.PENDING)
                .build();
    }

    // =========================================================
    // 공통 유틸
    // =========================================================

    private int calculatePrice(
            int pricePerNight,
            LocalDate checkIn,
            LocalDate checkOut
    ) {

        long days = ChronoUnit.DAYS.between(
                checkIn,
                checkOut
        );

        return (int) (pricePerNight * days);
    }

    // 예약번호 생성
    private String generateRsvNum() {

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
