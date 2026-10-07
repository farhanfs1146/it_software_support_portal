package com.forward.it_software_support_portal.entity;

import com.forward.it_software_support_portal.enums.Role;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_code",nullable = false, unique = true)
    private Long employeeCode;

    @Column(name = "full_name",nullable = false, length = 150)
    private String fullName;

    @Column(name = "email", nullable = false, length = 100)
    private String email;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "designation_id")
    private Long designationId;

    @Column(name = "role", nullable = false)
    @Enumerated(EnumType.STRING)
    private Role role;

    @Column(name = "active")
    private Boolean active = true;

    /**
     * BCrypt hash of the user's password, or null when no password has been set.
     *
     * <p>Null is the default and means the account cannot authenticate - enabling authentication
     * must not hand every pre-existing user a usable login. Never returned through any DTO:
     * UserResponse has no corresponding field, so there is no path by which a hash reaches a client.
     */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;
}
