package com.college.labbooking.catalog.web;

import com.college.labbooking.catalog.application.CatalogService;
import com.college.labbooking.catalog.application.CatalogService.BlackoutView;
import com.college.labbooking.catalog.application.CatalogService.EquipmentView;
import com.college.labbooking.catalog.application.CatalogService.LabView;
import com.college.labbooking.catalog.application.CatalogService.OpenRuleView;
import com.college.labbooking.catalog.application.CatalogService.PeriodView;
import com.college.labbooking.common.api.ApiEnvelope;
import com.college.labbooking.common.api.PageView;
import com.college.labbooking.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/course-periods")
    public ApiEnvelope<List<PeriodView>> periods() {
        return ApiEnvelope.ok(catalogService.periods());
    }

    @PutMapping("/course-periods/{periodNo}")
    @PreAuthorize("hasAuthority('settings:write')")
    public ApiEnvelope<PeriodView> updatePeriod(
            @PathVariable @Min(1) @Max(4) int periodNo,
            @Valid @RequestBody PeriodRequest request,
            Authentication authentication) {
        return ApiEnvelope.ok(catalogService.updatePeriod(periodNo, request, CurrentUser.from(authentication)));
    }

    @GetMapping("/labs")
    public ApiEnvelope<PageView<LabView>> labs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiEnvelope.ok(catalogService.labs(keyword, status, page, size));
    }

    @GetMapping("/labs/{id}")
    public ApiEnvelope<LabView> lab(@PathVariable long id) {
        return ApiEnvelope.ok(catalogService.lab(id));
    }

    @PostMapping("/labs")
    @PreAuthorize("hasAuthority('lab:write')")
    public ApiEnvelope<LabView> createLab(@Valid @RequestBody LabRequest request, Authentication authentication) {
        return ApiEnvelope.ok(catalogService.createLab(request, CurrentUser.from(authentication)));
    }

    @PutMapping("/labs/{id}")
    @PreAuthorize("hasAuthority('lab:write')")
    public ApiEnvelope<LabView> updateLab(
            @PathVariable long id, @Valid @RequestBody LabRequest request, Authentication authentication) {
        return ApiEnvelope.ok(catalogService.updateLab(id, request, CurrentUser.from(authentication)));
    }

    @GetMapping("/labs/{id}/open-rules")
    public ApiEnvelope<List<OpenRuleView>> openRules(@PathVariable long id) {
        return ApiEnvelope.ok(catalogService.openRules(id));
    }

    @PutMapping("/labs/{id}/open-rules")
    @PreAuthorize("hasAuthority('lab:write')")
    public ApiEnvelope<List<OpenRuleView>> replaceOpenRules(
            @PathVariable long id,
            @RequestBody @NotEmpty List<@Valid OpenRuleRequest> requests,
            Authentication authentication) {
        return ApiEnvelope.ok(catalogService.replaceOpenRules(id, requests, CurrentUser.from(authentication)));
    }

    @GetMapping("/labs/{id}/blackouts")
    public ApiEnvelope<List<BlackoutView>> blackouts(
            @PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiEnvelope.ok(catalogService.blackouts(id, from, to));
    }

    @PostMapping("/labs/{id}/blackouts")
    @PreAuthorize("hasAuthority('lab:write')")
    public ApiEnvelope<BlackoutView> createBlackout(
            @PathVariable long id, @Valid @RequestBody BlackoutRequest request, Authentication authentication) {
        return ApiEnvelope.ok(catalogService.createBlackout(id, request, CurrentUser.from(authentication)));
    }

    @DeleteMapping("/labs/{labId}/blackouts/{blackoutId}")
    @PreAuthorize("hasAuthority('lab:write')")
    public ApiEnvelope<Void> deleteBlackout(
            @PathVariable long labId, @PathVariable long blackoutId, Authentication authentication) {
        catalogService.deleteBlackout(labId, blackoutId, CurrentUser.from(authentication));
        return ApiEnvelope.ok(null);
    }

    @GetMapping("/equipment")
    @PreAuthorize("hasAuthority('equipment:read')")
    public ApiEnvelope<PageView<EquipmentView>> equipment(
            @RequestParam(required = false) Long labId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiEnvelope.ok(catalogService.equipment(labId, status, page, size));
    }

    @PostMapping("/equipment")
    @PreAuthorize("hasAuthority('equipment:write')")
    public ApiEnvelope<EquipmentView> createEquipment(
            @Valid @RequestBody EquipmentRequest request, Authentication authentication) {
        return ApiEnvelope.ok(catalogService.createEquipment(request, CurrentUser.from(authentication)));
    }

    @PutMapping("/equipment/{id}")
    @PreAuthorize("hasAuthority('equipment:write')")
    public ApiEnvelope<EquipmentView> updateEquipment(
            @PathVariable long id, @Valid @RequestBody EquipmentRequest request, Authentication authentication) {
        return ApiEnvelope.ok(catalogService.updateEquipment(id, request, CurrentUser.from(authentication)));
    }

    public record PeriodRequest(@NotBlank @Size(max = 40) String name, @NotNull LocalTime startTime,
            @NotNull LocalTime endTime, boolean enabled, @Min(0) long version) {}

    public record LabRequest(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 100) String building, @NotBlank @Size(max = 40) String roomNo,
            @Min(1) int capacity, @NotBlank @Size(max = 60) String labType, @Size(max = 5000) String description,
            @Size(max = 500) String imageUrl, @NotNull List<@NotBlank @Size(max = 40) String> tags,
            @jakarta.validation.constraints.Pattern(regexp = "ACTIVE|DISABLED|MAINTENANCE") String status,
            @jakarta.validation.constraints.Pattern(regexp = "AUTO|MANUAL") String studentApprovalMode,
            @jakarta.validation.constraints.Pattern(regexp = "AUTO|MANUAL") String teacherApprovalMode,
            boolean allowStudentBooking, @Min(1) @Max(4) int maxPeriodsPerUserDay, @Min(0) int advanceDays,
            @Min(0) int cancelBeforeMinutes, boolean requireCheckIn, @Min(0) long version) {}

    public record OpenRuleRequest(@Min(1) @Max(7) int dayOfWeek, @Min(1) @Max(4) int periodNo,
            LocalDate validFrom, LocalDate validTo) {}

    public record BlackoutRequest(@NotNull LocalDate bookingDate, @Min(1) @Max(4) int periodNo,
            @NotBlank @Size(max = 255) String reason) {}

    public record EquipmentRequest(@Min(1) long labId, @NotBlank @Size(max = 64) String assetCode,
            @NotBlank @Size(max = 80) String category, @NotBlank @Size(max = 100) String name,
            @Size(max = 100) String model, @Min(1) int totalQuantity, @Size(max = 100) String requiredQualification,
            @jakarta.validation.constraints.Pattern(regexp = "AVAILABLE|MAINTENANCE|RETIRED") String status,
            @Min(0) long version) {}
}
