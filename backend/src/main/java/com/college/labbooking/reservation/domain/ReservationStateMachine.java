package com.college.labbooking.reservation.domain;

import com.college.labbooking.common.exception.AppException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ReservationStateMachine {
    private static final Map<ReservationStatus, Set<ReservationStatus>> ALLOWED = Map.of(
            ReservationStatus.PENDING_APPROVAL,
            EnumSet.of(ReservationStatus.APPROVED, ReservationStatus.REJECTED, ReservationStatus.CANCELLED),
            ReservationStatus.APPROVED,
            EnumSet.of(ReservationStatus.CANCELLED, ReservationStatus.IN_USE,
                    ReservationStatus.COMPLETED, ReservationStatus.NO_SHOW),
            ReservationStatus.IN_USE,
            EnumSet.of(ReservationStatus.COMPLETED),
            ReservationStatus.REJECTED,
            EnumSet.noneOf(ReservationStatus.class),
            ReservationStatus.CANCELLED,
            EnumSet.noneOf(ReservationStatus.class),
            ReservationStatus.COMPLETED,
            EnumSet.noneOf(ReservationStatus.class),
            ReservationStatus.NO_SHOW,
            EnumSet.noneOf(ReservationStatus.class));

    public void requireTransition(ReservationStatus from, ReservationStatus to) {
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new AppException(HttpStatus.CONFLICT, "RESERVATION_STATUS_INVALID",
                    "预约状态不能从 " + from + " 变更为 " + to);
        }
    }
}
