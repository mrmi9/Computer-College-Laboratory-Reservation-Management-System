package com.college.labbooking.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "sys_role")
public class RoleEntity {
    @Id
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @Version
    private long version;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "sys_role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<PermissionEntity> permissions = new LinkedHashSet<>();

    protected RoleEntity() {}

    public String getCode() {
        return code;
    }

    public Set<PermissionEntity> getPermissions() {
        return Set.copyOf(permissions);
    }
}
