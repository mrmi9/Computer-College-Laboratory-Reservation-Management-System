package com.college.labbooking.identity.web;

import com.college.labbooking.common.api.ApiEnvelope;
import com.college.labbooking.common.api.PageView;
import com.college.labbooking.identity.application.IdentityAdminService;
import com.college.labbooking.identity.application.IdentityAdminService.ImportTaskView;
import com.college.labbooking.identity.application.IdentityAdminService.PermissionView;
import com.college.labbooking.identity.application.IdentityAdminService.RoleAdminView;
import com.college.labbooking.identity.application.IdentityAdminService.UserAdminView;
import com.college.labbooking.identity.application.IdentityAdminService.UserMutation;
import com.college.labbooking.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class IdentityAdminController {
    private final IdentityAdminService service;

    public IdentityAdminController(IdentityAdminService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public ApiEnvelope<PageView<UserAdminView>> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|DISABLED|LOCKED") String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiEnvelope.ok(service.users(keyword, status, page, size));
    }

    @GetMapping("/users/{id}")
    public ApiEnvelope<UserAdminView> user(@PathVariable long id) {
        return ApiEnvelope.ok(service.user(id));
    }

    @PostMapping("/users")
    public ApiEnvelope<UserAdminView> createUser(
            @Valid @RequestBody CreateUserRequest request, Authentication authentication) {
        return ApiEnvelope.ok(service.createUser(request.mutation(), request.roles(), request.initialPassword(),
                CurrentUser.from(authentication)));
    }

    @PutMapping("/users/{id}")
    public ApiEnvelope<UserAdminView> updateUser(
            @PathVariable long id, @Valid @RequestBody UpdateUserRequest request, Authentication authentication) {
        return ApiEnvelope.ok(service.updateUser(id, request.mutation(), request.version(),
                CurrentUser.from(authentication)));
    }

    @PutMapping("/users/{id}/roles")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') and hasAuthority('role:write')")
    public ApiEnvelope<UserAdminView> replaceRoles(
            @PathVariable long id, @Valid @RequestBody UserRolesRequest request, Authentication authentication) {
        return ApiEnvelope.ok(service.replaceUserRoles(id, request.roles(), request.version(),
                CurrentUser.from(authentication)));
    }

    @GetMapping("/roles")
    public ApiEnvelope<List<RoleAdminView>> roles() {
        return ApiEnvelope.ok(service.roles());
    }

    @PostMapping("/roles")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') and hasAuthority('role:write')")
    public ApiEnvelope<RoleAdminView> createRole(
            @Valid @RequestBody CreateRoleRequest request, Authentication authentication) {
        return ApiEnvelope.ok(service.createRole(request.code(), request.name(), request.description(),
                request.permissions(), CurrentUser.from(authentication)));
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') and hasAuthority('role:write')")
    public ApiEnvelope<RoleAdminView> updateRole(
            @PathVariable long id, @Valid @RequestBody UpdateRoleRequest request, Authentication authentication) {
        return ApiEnvelope.ok(service.updateRole(id, request.name(), request.description(), request.permissions(),
                request.version(), CurrentUser.from(authentication)));
    }

    @GetMapping("/permissions")
    public ApiEnvelope<List<PermissionView>> permissions() {
        return ApiEnvelope.ok(service.permissions());
    }

    @PostMapping(value = "/users/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiEnvelope<ImportTaskView> importUsers(
            @RequestPart("file") MultipartFile file, Authentication authentication) throws IOException {
        return ApiEnvelope.ok(service.importUsers(file.getOriginalFilename(), file.getBytes(),
                CurrentUser.from(authentication)));
    }

    @GetMapping("/import-tasks/{id}")
    public ApiEnvelope<ImportTaskView> importTask(@PathVariable UUID id) {
        return ApiEnvelope.ok(service.importTask(id));
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(max = 64) String realName,
            @NotNull @Pattern(regexp = "STUDENT|TEACHER|STAFF") String userType,
            @Size(max = 100) String department,
            @Size(max = 128) String email,
            @Size(max = 32) String phone,
            @NotNull @Pattern(regexp = "ACTIVE|DISABLED|LOCKED") String status,
            @NotEmpty Set<@NotBlank @Size(max = 40) String> roles,
            @NotBlank @Size(min = 12, max = 128) String initialPassword) {
        UserMutation mutation() {
            return new UserMutation(username, realName, userType, department, email, phone, status);
        }
    }

    public record UpdateUserRequest(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(max = 64) String realName,
            @NotNull @Pattern(regexp = "STUDENT|TEACHER|STAFF") String userType,
            @Size(max = 100) String department,
            @Size(max = 128) String email,
            @Size(max = 32) String phone,
            @NotNull @Pattern(regexp = "ACTIVE|DISABLED|LOCKED") String status,
            @Min(0) long version) {
        UserMutation mutation() {
            return new UserMutation(username, realName, userType, department, email, phone, status);
        }
    }

    public record UserRolesRequest(@NotEmpty Set<@NotBlank @Size(max = 40) String> roles, @Min(0) long version) {}

    public record CreateRoleRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{1,39}") String code,
            @NotBlank @Size(max = 80) String name,
            @Size(max = 255) String description,
            @NotNull Set<@NotBlank @Size(max = 80) String> permissions) {}

    public record UpdateRoleRequest(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 255) String description,
            @NotNull Set<@NotBlank @Size(max = 80) String> permissions,
            @Min(0) long version) {}
}
