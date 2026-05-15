package com.forward.it_software_support_portal.controller;


import com.forward.it_software_support_portal.dto.request.CreateUserRequest;
import com.forward.it_software_support_portal.dto.response.UserResponse;
import com.forward.it_software_support_portal.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public UserResponse createUser(@Valid @RequestBody CreateUserRequest request) {
        // @RequestBody: Converts JSON data-request into Java Object.
        return userService.createUser(request);
    }

    @GetMapping("/{id}")
    public UserResponse getUserById(@PathVariable Long id) {
        return userService.getUserById(id);
    }

//    @GetMapping
//    public Page<UserResponse> getAllUsers(
//            @RequestParam(defaultValue = "0") int page,
//            @RequestParam(defaultValue = "10") int size
//    ) {
//
//        return userService.getAllUsers(page, size);
//    }

    // As above, we add Pagination approach because there can be 10K or more records in data to load at once.
    // In real-world applications:
    // Pagination alone is not enough
    // Search alone is not enough
    // Good Approach
    // Both work together.
    // Real-world systems use:
    // Server-side search
    // Why?
    // Because database may contain:100,000 users
    // 2 million records
    // huge datasets
    // Angular should NOT load all records.
    // Instead:
    // Angular → Spring Boot → Database.

    // IMPORTANT CONCEPT
    // @RequestParam
    // Means:
    // Read value from URL.
    // Example:
    // api/users?page=0&size=5&search=Farhan
    // This is EXACTLY how large systems:
    //
    //HRMS
    //ERP
    //Banking
    //Hospital systems
    //Ticketing systems
    //
    //manage huge datasets efficiently.
    //
    //Because:
    //
    //❌ Loading 100,000 users at once is dangerous.
    //
    //Instead:
    //
    //✅ Backend sends only needed records.

    @GetMapping
    public Page<UserResponse> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            // we should add optional search parameter s well,
            @RequestParam(defaultValue = "") String searchTerm
    ) {
        return userService.getAllUsers(page, size,searchTerm);
    }

} // end of controller class.
