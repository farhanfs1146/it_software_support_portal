package com.forward.it_software_support_portal.service.impl;


import com.forward.it_software_support_portal.dto.request.CreateUserRequest;
import com.forward.it_software_support_portal.dto.response.UserResponse;
import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    @Override
    public UserResponse createUser(CreateUserRequest request) {

        userRepository.findByEmployeeCode(request.getEmployeeCode())
                .ifPresent(user -> {
                    throw new RuntimeException("Employee code already exists");
                });

        userRepository.findByEmail(request.getEmail())
                .ifPresent(user -> {
                    throw new RuntimeException("Email already exists");
                });

        User user = new User();
        user.setEmployeeCode(request.getEmployeeCode());
        user.setFullName(request.getFullName());
        user.setEmail(request.getEmail());
        user.setDepartmentId(request.getDepartmentId());
        user.setDesignationId(request.getDesignationId());
        user.setRole(request.getRole());
        user.setActive(request.getActive());

        User savedUser = userRepository.save(user);

        return mapToResponse(savedUser);
    }

    @Override
    public UserResponse getUserById(Long id) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return mapToResponse(user);
    }

    @Override
    public Page<UserResponse> getAllUsers(int page, int size, String searchTerm) {

        Pageable pageable = PageRequest.of(page, size); // for pagination

        // now extra feature of search is also added. we have to create its logic as well.
        return userRepository.findByFullNameContainingIgnoreCase(searchTerm, pageable)
                .map(this::mapToResponse);
    }

    private UserResponse mapToResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .employeeCode(user.getEmployeeCode())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .departmentId(user.getDepartmentId())
                .designationId(user.getDesignationId())
                .role(user.getRole().name())
                .active(user.getActive())
                .build();
    }
}
