package com.college.labbooking.notification.web;

import com.college.labbooking.common.api.ApiEnvelope;
import com.college.labbooking.common.api.PageView;
import com.college.labbooking.notification.application.NotificationService;
import com.college.labbooking.notification.application.NotificationService.NotificationView;
import com.college.labbooking.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ApiEnvelope<PageView<NotificationView>> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication) {
        return ApiEnvelope.ok(notificationService.list(CurrentUser.from(authentication), unreadOnly, page, size));
    }

    @PutMapping("/{id}/read")
    public ApiEnvelope<NotificationView> markRead(@PathVariable long id, Authentication authentication) {
        return ApiEnvelope.ok(notificationService.markRead(id, CurrentUser.from(authentication)));
    }

    @PutMapping("/read-all")
    public ApiEnvelope<Integer> markAllRead(Authentication authentication) {
        return ApiEnvelope.ok(notificationService.markAllRead(CurrentUser.from(authentication)));
    }
}
