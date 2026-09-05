package com.college.labbooking.reservation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.college.labbooking.common.exception.AppException;
import com.college.labbooking.reservation.domain.ReservationStateMachine;
import com.college.labbooking.reservation.domain.ReservationStatus;
import org.junit.jupiter.api.Test;

class ReservationStateMachineTest {
    private final ReservationStateMachine stateMachine = new ReservationStateMachine();

    @Test
    void permitsDocumentedLifecycle() {
        assertThatCode(() -> stateMachine.requireTransition(
                        ReservationStatus.PENDING_APPROVAL, ReservationStatus.APPROVED))
                .doesNotThrowAnyException();
        assertThatCode(() -> stateMachine.requireTransition(ReservationStatus.APPROVED, ReservationStatus.IN_USE))
                .doesNotThrowAnyException();
        assertThatCode(() -> stateMachine.requireTransition(ReservationStatus.IN_USE, ReservationStatus.COMPLETED))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsTerminalAndSkippedTransitions() {
        assertThatThrownBy(() -> stateMachine.requireTransition(
                        ReservationStatus.REJECTED, ReservationStatus.APPROVED))
                .isInstanceOf(AppException.class)
                .extracting(exception -> ((AppException) exception).code())
                .isEqualTo("RESERVATION_STATUS_INVALID");
        assertThatThrownBy(() -> stateMachine.requireTransition(
                        ReservationStatus.APPROVED, ReservationStatus.REJECTED))
                .isInstanceOf(AppException.class);
    }
}
