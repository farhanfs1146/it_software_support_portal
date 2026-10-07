package com.forward.it_software_support_portal.repository;


import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.enums.Role;
import com.forward.it_software_support_portal.repository.projection.UserRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByEmployeeCode(Long employeeCode);

    /** Used by the bootstrap administrator check; counts rather than loads, so it stays cheap. */
    long countByRole(Role role);

    /**
     * One page of users, projected so a listing never reads {@code password_hash} (Phase 6).
     *
     * <p>Unlike the ticket list, this is not about N+1 - {@code User} has no associations. It is about not
     * selecting a password hash to build a directory listing; see {@link UserRow}.
     *
     * <p>An explicit {@code countQuery} is required because the main query is a constructor expression,
     * which Spring Data cannot rewrite into a count on its own. With it, a page costs two statements
     * whatever the number of users.
     */
    @Query(value = """
            select new com.forward.it_software_support_portal.repository.projection.UserRow(
                u.id, u.employeeCode, u.fullName, u.email, u.departmentId, u.designationId,
                u.role, u.active)
            from User u
            """,
            countQuery = "select count(u.id) from User u")
    Page<UserRow> findUserRows(Pageable pageable);
}
