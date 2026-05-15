package com.forward.it_software_support_portal.repository;


import com.forward.it_software_support_portal.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByEmployeeCode(Long employeeCode);

    // IMPORTANT CONCEPT
    // Containing(Work as LIKE %'string search'%): Keyword Means SQL LIKE Filter see demo:  LIKE %Farhan%

    // IMPORTANT CONCEPT
    // IgnoreCase Keyword(Not Actually keyword it represents this query) Means:
    // if user types like in any Case database will see this after convert into lower-case and return matching records.
    // Farhan
    // farHaN
    // FARHan ALi
    // etc.
    // all will work.

    Page<User> findByFullNameContainingIgnoreCase(String fullName, Pageable pageable); // Pageable here purpose pass total records to see and page size etc.
    // IMPORTANT CONCEPT
    // Spring Data JPA automatically creates SQL from method name.
    // This:
    // findByFullNameContainingIgnoreCase
    // automatically becomes:
    // SELECT * FROM users WHERE LOWER(full_name) LIKE LOWER('%farhan%')
    // This is one of Spring Boot's most powerful features.
}
