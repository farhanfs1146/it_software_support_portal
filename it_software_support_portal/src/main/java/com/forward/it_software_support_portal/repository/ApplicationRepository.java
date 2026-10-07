package com.forward.it_software_support_portal.repository;

import com.forward.it_software_support_portal.entity.Application;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    /**
     * Paged active-application lookup (Phase 6).
     *
     * <p>Spring Data derives the matching count query from the derived method name, so a page costs two
     * statements whatever the catalogue size. No projection is needed: Application has no associations and
     * every column it has appears in the response.
     */
    Page<Application> findByActiveTrue(Pageable pageable);
}
