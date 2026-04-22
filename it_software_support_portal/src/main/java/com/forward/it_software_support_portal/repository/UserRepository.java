package com.forward.it_software_support_portal.repository;


import com.forward.it_software_support_portal.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
