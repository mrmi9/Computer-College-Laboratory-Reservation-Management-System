package com.college.labbooking.statistics.web;

import com.college.labbooking.common.api.ApiEnvelope;
import com.college.labbooking.security.CurrentUser;
import com.college.labbooking.statistics.application.StatisticsService;
import com.college.labbooking.statistics.application.StatisticsService.EquipmentUsageView;
import com.college.labbooking.statistics.application.StatisticsService.LabUsageView;
import com.college.labbooking.statistics.application.StatisticsService.OverviewView;
import com.college.labbooking.statistics.application.StatisticsService.PeakHourView;
import com.college.labbooking.statistics.application.StatisticsService.StatisticsFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/statistics")
@PreAuthorize("hasAuthority('statistics:read')")
public class StatisticsController {
    private final StatisticsService service;

    public StatisticsController(StatisticsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiEnvelope<OverviewView> overview(
            @Valid @ModelAttribute FilterParameters filter, Authentication authentication) {
        return ApiEnvelope.ok(service.overview(filter.toFilter(), CurrentUser.from(authentication)));
    }

    @GetMapping("/lab-usage")
    public ApiEnvelope<List<LabUsageView>> labUsage(
            @Valid @ModelAttribute FilterParameters filter, Authentication authentication) {
        return ApiEnvelope.ok(service.labUsage(filter.toFilter(), CurrentUser.from(authentication)));
    }

    @GetMapping("/peak-hours")
    public ApiEnvelope<List<PeakHourView>> peakHours(
            @Valid @ModelAttribute FilterParameters filter, Authentication authentication) {
        return ApiEnvelope.ok(service.peakHours(filter.toFilter(), CurrentUser.from(authentication)));
    }

    @GetMapping("/equipment-ranking")
    public ApiEnvelope<List<EquipmentUsageView>> equipmentRanking(
            @Valid @ModelAttribute FilterParameters filter,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit,
            Authentication authentication) {
        return ApiEnvelope.ok(service.equipmentRanking(filter.toFilter(), CurrentUser.from(authentication), limit));
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> export(
            @Valid @ModelAttribute FilterParameters filter, Authentication authentication) {
        byte[] content = service.exportReservations(filter.toFilter(), CurrentUser.from(authentication));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=reservation-report.csv")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(content);
    }

    public record FilterParameters(
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Long labId,
            @Pattern(regexp = "STUDENT|TEACHER") String applicantType,
            @Pattern(regexp = "PENDING_APPROVAL|APPROVED|REJECTED|CANCELLED|IN_USE|COMPLETED|NO_SHOW")
                    String status) {
        StatisticsFilter toFilter() {
            return new StatisticsFilter(from, to, labId, applicantType, status);
        }
    }

}
