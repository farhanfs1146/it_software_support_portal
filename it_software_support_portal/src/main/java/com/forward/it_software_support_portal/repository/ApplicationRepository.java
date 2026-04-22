package com.forward.it_software_support_portal.repository;

import com.forward.it_software_support_portal.entity.Application;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, Long> {
}
