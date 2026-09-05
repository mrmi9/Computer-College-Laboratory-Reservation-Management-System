package com.college.labbooking.administration.web;

import com.college.labbooking.administration.application.SystemAdminService;
import com.college.labbooking.administration.application.SystemAdminService.AuditLogView;
import com.college.labbooking.administration.application.SystemAdminService.SettingView;
import com.college.labbooking.common.api.ApiEnvelope;
import com.college.labbooking.common.api.PageView;
import com.college.labbooking.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin")
public class SystemAdminController {
    private final SystemAdminService service;

    public SystemAdminController(SystemAdminService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') and hasAuthority('settings:write')")
    public ApiEnvelope<List<SettingView>> settings() {
        return ApiEnvelope.ok(service.settings());
    }

    @PutMapping("/settings/{key}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') and hasAuthority('settings:write')")
    public ApiEnvelope<SettingView> updateSetting(
            @PathVariable @Size(max = 100) String key,
            @Valid @RequestBody SettingRequest request,
            Authentication authentication) {
        return ApiEnvelope.ok(service.updateSetting(key, request.value(), request.description(), request.version(),
                CurrentUser.from(authentication)));
    }

    @GetMapping("/audit-logs")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') and hasAuthority('audit:read')")
    public ApiEnvelope<PageView<AuditLogView>> auditLogs(
            @RequestParam(required = false) @Size(max = 80) String action,
            @RequestParam(required = false) @Size(max = 64) String actorUsername,
            @RequestParam(required = false) @Size(max = 60) String targetType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiEnvelope.ok(service.auditLogs(action, actorUsername, targetType, from, to, page, size));
    }

    public record SettingRequest(@NotNull JsonNode value, @Size(max = 255) String description,
            @Min(0) long version) {}
}
