package com.forward.it_software_support_portal.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ApplicationResponse {

    private Long id;
    private String appName;
    private String moduleName;
//    private String description;
    private boolean active;
}
