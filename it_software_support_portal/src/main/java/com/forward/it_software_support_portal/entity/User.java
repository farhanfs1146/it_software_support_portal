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

    private Long department_id;

    private Long designation_id;

    @Enumerated(EnumType.STRING)
    private Role role;

    private boolean active = true;
}