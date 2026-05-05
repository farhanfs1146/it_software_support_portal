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

    @Column(name = "email",nullable = false, length = 150)
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
}