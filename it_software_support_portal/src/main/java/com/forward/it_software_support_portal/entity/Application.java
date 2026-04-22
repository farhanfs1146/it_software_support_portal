package com.forward.it_software_support_portal.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "applications")
@Getter
@Setter
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String appName;

    private String moduleName;

    private boolean active = true;
}