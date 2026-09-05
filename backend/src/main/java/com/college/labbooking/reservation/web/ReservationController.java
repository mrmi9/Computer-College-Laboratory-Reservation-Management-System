package com.college.labbooking.reservation.web;

import com.college.labbooking.common.api.ApiEnvelope;
import com.college.labbooking.common.api.PageView;
import com.college.labbooking.reservation.application.ReservationService;
import com.college.labbooking.reservation.application.ReservationService.AvailableLabView;
import com.college.labbooking.reservation.application.ReservationService.ReservationView;
import com.college.labbooking.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1")
public class ReservationController {
    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @GetMapping("/availability/labs")
    @PreAuthorize("hasAuthority('lab:read')")
    public ApiEnvelope<List<AvailableLabView>> availability(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bookingDate,
            @RequestParam @Min(1) @Max(4) int periodNo,
            @RequestParam(defaultValue = "1") @Min(1) int capacity,
            @RequestParam(required = false) List<Long> equipmentIds,
            Authentication authentication) {
        return ApiEnvelope.ok(reservationService.availability(
                bookingDate, periodNo, capacity, equipmentIds == null ? List.of() : equipmentIds,
                CurrentUser.from(authentication)));
    }

    @PostMapping("/reservations")
    @PreAuthorize("hasAuthority('reservation:create')")
    public ApiEnvelope<ReservationView> create(
            @Valid @RequestBody CreateReservationRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        return ApiEnvelope.ok(reservationService.create(request, idempotencyKey, CurrentUser.from(authentication)));
    }

    @GetMapping("/reservations/my")
    @PreAuthorize("hasAuthority('reservation:read:self')")
    public ApiEnvelope<PageView<ReservationView>> mine(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication) {
        return ApiEnvelope.ok(reservationService.mine(CurrentUser.from(authentication), status, page, size));
    }

    @GetMapping("/reservations/{id}")
    public ApiEnvelope<ReservationView> detail(@PathVariable long id, Authentication authentication) {
        return ApiEnvelope.ok(reservationService.detail(id, CurrentUser.from(authentication)));
    }

    @PostMapping("/reservations/{id}/cancel")
    @PreAuthorize("hasAuthority('reservation:read:self')")
    public ApiEnvelope<ReservationView> cancel(
            @PathVariable long id, @Valid @RequestBody CancelRequest request, Authentication authentication) {
        return ApiEnvelope.ok(reservationService.cancelByApplicant(id, request.reason(), CurrentUser.from(authentication)));
    }

    @GetMapping("/admin/reservations")
    @PreAuthorize("hasAuthority('reservation:read:managed')")
    public ApiEnvelope<PageView<ReservationView>> managed(
            @RequestParam(required = false) Long labId,
            @RequestParam(required = false) String applicantType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication) {
        return ApiEnvelope.ok(reservationService.managed(
                CurrentUser.from(authentication), labId, applicantType, status, page, size));
    }

    @PostMapping("/admin/reservations/{id}/approve")
    @PreAuthorize("hasAuthority('reservation:approve')")
    public ApiEnvelope<ReservationView> approve(
            @PathVariable long id,
            @Valid @RequestBody DecisionRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        return ApiEnvelope.ok(reservationService.approve(
                id, request.version(), request.comment(), idempotencyKey, CurrentUser.from(authentication)));
    }

    @PostMapping("/admin/reservations/{id}/reject")
    @PreAuthorize("hasAuthority('reservation:approve')")
    public ApiEnvelope<ReservationView> reject(
            @PathVariable long id,
            @Valid @RequestBody RejectRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        return ApiEnvelope.ok(reservationService.reject(
                id, request.version(), request.reason(), idempotencyKey, CurrentUser.from(authentication)));
    }

    @PostMapping("/admin/reservations/{id}/cancel")
    @PreAuthorize("hasAuthority('reservation:cancel:any')")
    public ApiEnvelope<ReservationView> adminCancel(
            @PathVariable long id, @Valid @RequestBody AdminCancelRequest request, Authentication authentication) {
        return ApiEnvelope.ok(reservationService.cancelByAdmin(
                id, request.version(), request.reason(), CurrentUser.from(authentication)));
    }

    @GetMapping("/admin/reservations/{id}/history")
    @PreAuthorize("hasAuthority('reservation:read:managed')")
    public ApiEnvelope<List<ReservationService.HistoryView>> history(
            @PathVariable long id, Authentication authentication) {
        return ApiEnvelope.ok(reservationService.history(id, CurrentUser.from(authentication)));
    }

    public record EquipmentItemRequest(@Min(1) long equipmentId, @Min(1) int quantity) {}

    public record CreateReservationRequest(@Min(1) long labId, @NotBlank @Size(max = 120) String title,
            @NotBlank @Size(max = 5000) String purpose, @Min(1) int participantCount,
            @NotNull LocalDate bookingDate, @Min(1) @Max(4) int periodNo,
            @NotBlank @Size(max = 120) String projectOrCourse, @NotBlank @Size(max = 32) String contactPhone,
            @NotNull List<@Valid EquipmentItemRequest> equipmentItems, @Size(max = 5000) String remark) {}

    public record CancelRequest(@NotBlank @Size(max = 500) String reason) {}

    public record DecisionRequest(@Min(0) long version, @Size(max = 1000) String comment) {}

    public record RejectRequest(@Min(0) long version, @NotBlank @Size(max = 1000) String reason) {}

    public record AdminCancelRequest(@Min(0) long version, @NotBlank @Size(max = 1000) String reason) {}
}
