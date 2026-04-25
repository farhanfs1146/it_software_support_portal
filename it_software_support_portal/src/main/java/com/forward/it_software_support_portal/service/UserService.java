package com.forward.it_software_support_portal.service;

import com.forward.it_software_support_portal.dto.request.user.CreateUserRequest;
import com.forward.it_software_support_portal.dto.response.user.UserResponse;

import java.util.List;

public interface UserService {

    UserResponse createUser(CreateUserRequest request);

    UserResponse getUserById(Long id);

    List<UserResponse> getAllUsers();
}
