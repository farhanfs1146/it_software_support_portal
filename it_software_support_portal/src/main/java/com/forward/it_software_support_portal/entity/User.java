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

    private Long employeeCode;

    private String fullName;

    private String email;

    private Long department_id;

    private Long designation_id;

    @Enumerated(EnumType.STRING)
    private Role role;

    private boolean active = true;
}