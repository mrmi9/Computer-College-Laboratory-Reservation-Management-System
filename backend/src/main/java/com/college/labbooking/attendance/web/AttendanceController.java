package com.college.labbooking.attendance.web;

import com.college.labbooking.attendance.application.AttendanceService;
import com.college.labbooking.attendance.application.AttendanceService.AttendanceView;
import com.college.labbooking.common.api.ApiEnvelope;
import com.college.labbooking.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AttendanceController {
    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @PostMapping("/reservations/{id}/check-in")
    public ApiEnvelope<AttendanceView> checkIn(
            @PathVariable long id, @Valid @RequestBody AttendanceRequest request, Authentication authentication) {
        return ApiEnvelope.ok(attendanceService.checkIn(
                id, request.actualParticipantCount(), request.note(), CurrentUser.from(authentication), false));
    }

    @PostMapping("/reservations/{id}/check-out")
    public ApiEnvelope<AttendanceView> checkOut(
            @PathVariable long id, @Valid @RequestBody AttendanceRequest request, Authentication authentication) {
        return ApiEnvelope.ok(attendanceService.checkOut(
                id, request.actualParticipantCount(), request.note(), CurrentUser.from(authentication), false));
    }

    @PostMapping("/admin/reservations/{id}/check-in")
    @PreAuthorize("hasAuthority('checkin:manage')")
    public ApiEnvelope<AttendanceView> adminCheckIn(
            @PathVariable long id, @Valid @RequestBody AttendanceRequest request, Authentication authentication) {
        return ApiEnvelope.ok(attendanceService.checkIn(
                id, request.actualParticipantCount(), request.note(), CurrentUser.from(authentication), true));
    }

    @PostMapping("/admin/reservations/{id}/check-out")
    @PreAuthorize("hasAuthority('checkin:manage')")
    public ApiEnvelope<AttendanceView> adminCheckOut(
            @PathVariable long id, @Valid @RequestBody AttendanceRequest request, Authentication authentication) {
        return ApiEnvelope.ok(attendanceService.checkOut(
                id, request.actualParticipantCount(), request.note(), CurrentUser.from(authentication), true));
    }

    public record AttendanceRequest(@Min(0) Integer actualParticipantCount, @Size(max = 500) String note) {}
}
