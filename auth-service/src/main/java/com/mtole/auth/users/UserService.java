package com.mtole.auth.users;


import com.mtole.auth.users.dto.UserCreateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserService {
    private final UserRepository userRepository;
    private static final Logger log = LoggerFactory.getLogger(UserService.class);


    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;


        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }
    public User create(UserCreateRequest request){
        String normalizedEmail = request.email().toLowerCase();
        log.info("Creating user with email={}", normalizedEmail);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateEmailException("Email already registered: " + normalizedEmail);
        }
        User entity = userMapper.toEntity(request);
        entity.setPassword(passwordEncoder.encode(request.password()));
        entity.setEmail(normalizedEmail);
        User createdUser = userRepository.save(entity);
        log.info("Created user with id={}", createdUser.getId());
        return createdUser;
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }
    public boolean deleteById(Long id) {
        if (!userRepository.existsById(id)) {
            log.warn("Cannot delete user with id={}: not found", id);
            return false;
        }
        log.info("Deleted user with id={} (tasks and categories cascade via FK)", id);
        userRepository.deleteById(id);
        log.info("Deleted user with id={}", id);
        return true;
    }
}
