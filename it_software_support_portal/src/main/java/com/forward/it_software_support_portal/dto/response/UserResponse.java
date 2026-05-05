package com.forward.it_software_support_portal.dto.response;


import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserResponse {

    private Long id;
    private Long employeeCode;
    private String fullName;
    private String email;
    private Long departmentId;
    private Long designationId;
    private String role;
    private Boolean active;
}
