package com.forward.it_software_support_portal.service;

import com.forward.it_software_support_portal.dto.request.CreateUserRequest;
import com.forward.it_software_support_portal.dto.response.UserResponse;
import org.springframework.data.domain.Page;

import java.util.List;

public interface UserService {

    UserResponse createUser(CreateUserRequest request);

    UserResponse getUserById(Long id);

    Page<UserResponse> getAllUsers(int  page, int size, String searchTerm);
}
