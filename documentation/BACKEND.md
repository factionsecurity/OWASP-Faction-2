# Backend Architecture & Services

## Overview

The backend is a Spring Boot 3.x application built with Java 17, providing a RESTful API for the FACTION Client Portal. The architecture follows clean separation of concerns with distinct layers for controllers, services, repositories, and data transfer objects (DTOs).

### Technology Stack
- **Java 17**: Programming language
- **Spring Boot 3.x**: Web framework with auto-configuration
- **MongoDB**: Document-oriented NoSQL database
- **JWT (JSON Web Tokens)**: Stateless authentication mechanism
- **Testcontainers**: Integration testing with real containers
- **Swagger/OpenAPI**: API documentation
- **Lombok**: Reduces boilerplate code
- **MapStruct**: Automatic DTO mapping
- **Maven**: Build tool

## Project Structure
```
backend/
├── src/main/java/com/faction/clientportal/
│   ├── config/              # Spring configuration classes
│   ├── model/               # Domain models (User, Role, Organization)
│   ├── repository/          # MongoDB repositories (DAO layer)
│   ├── service/             # Business logic services
│   │   ├── AuthService.java     # Authentication and login logic
│   │   ├── UserService.java     # User management operations
│   │   ├── AssessmentService.java # Assessment lifecycle management
│   │   └── ReportTemplateService.java # Report template creation and management
│   ├── controller/          # REST API endpoints
│   │   ├── UserController.java      # User API endpoints
│   │   ├── AssessmentController.java # Assessment API endpoints
│   │   └── ReportTemplateController.java # Report template endpoints
│   ├── dto/                 # Data Transfer Objects (request/response models)
│   │   ├── UserDto.java           # User data model
│   │   ├── AssessmentDto.java     # Assessment data model
│   │   └── ReportTemplateDto.java # Report template data model
│   ├── security/            # Authentication and authorization components
│   │   ├── JwtTokenProvider.java  # JWT token generation/validation
│   │   └── JwtAuthenticationFilter.java # Authentication filter
│   └── exception/           # Custom exception handlers
│       └── GlobalExceptionHandler.java
├── Dockerfile               # Containerization configuration
├── pom.xml                  # Maven build configuration
└── CLAUDE.md                # Development guidelines for Claude AI
```

## Configuration (config/)

### SecurityConfig.java

```java
// src/main/java/com/faction/clientportal/config/SecurityConfig.java
package com.faction.clientportal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }
    
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        
        return http.build();
    }
    
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
    
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }
}
```

### MongoConfig.java

```java
// src/main/java/com/faction/clientportal/config/MongoConfig.java
package com.faction.clientportal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

@Configuration
public class MongoConfig {
    
    @Bean
    public MongoTemplate mongoTemplate() {
        // This will be configured via application.properties or environment variables
        return new MongoTemplate(new SimpleMongoClientDatabaseFactory("mongodb://localhost:27017/faction"));
    }
}
```

## Data Transfer Objects (dto/)

### UserDto.java

```java
// src/main/java/com/faction/clientportal/dto/UserDto.java
package com.faction.clientportal.dto;

import lombok.Data;
import java.util.List;
import java.time.LocalDateTime;

@Data
public class UserDto {
    private String id;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private Boolean active;
    private LocalDateTime lastLogin;
    private List<String> roles;
    private List<String> permissions;
    private String teamId;
    private String organizationId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### AssessmentDto.java

```java
// src/main/java/com/faction/clientportal/dto/AssessmentDto.java
package com.faction.clientportal.dto;

import lombok.Data;
import java.util.List;
import java.time.LocalDateTime;

@Data
public class AssessmentDto {
    private String id;
    private String name;
    private String description;
    private String status; // planned, in-progress, completed
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private String applicationId;
    private String assessmentTypeId;
    private String teamId;
    private List<String> assessorIds;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### ReportTemplateDto.java

```java
// src/main/java/com/faction/clientportal/dto/ReportTemplateDto.java
package com.faction.clientportal.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ReportTemplateDto {
    private String id;
    private String name;
    private String description;
    private String content; // JSON or HTML template content
    private String type; // assessment, vulnerability
    private Boolean defaultTemplate;
    private String organizationId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

## Repository Layer (repository/)

### UserRepository.java

```java
// src/main/java/com/faction/clientportal/repository/UserRepository.java
package com.faction.clientportal.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import com.faction.clientportal.model.User;

@Repository
public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
}
```

### AssessmentRepository.java

```java
// src/main/java/com/faction/clientportal/repository/AssessmentRepository.java
package com.faction.clientportal.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import com.faction.clientportal.model.Assessment;

@Repository
public interface AssessmentRepository extends MongoRepository<Assessment, String> {
    List<Assessment> findByTeamId(String teamId);
    List<Assessment> findByStatus(String status);
    List<Assessment> findByApplicationId(String applicationId);
}
```

### ReportTemplateRepository.java

```java
// src/main/java/com/faction/clientportal/repository/ReportTemplateRepository.java
package com.faction.clientportal.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import com.faction.clientportal.model.ReportTemplate;

@Repository
public interface ReportTemplateRepository extends MongoRepository<ReportTemplate, String> {
    List<ReportTemplate> findByOrganizationId(String organizationId);
    List<ReportTemplate> findByType(String type);
}
```

## Service Layer (service/)

### AuthService.java

```java
// src/main/java/com/faction/clientportal/service/AuthService.java
package com.faction.clientportal.service;

import com.faction.clientportal.dto.LoginRequest;
import com.faction.clientportal.dto.LoginResponse;
import com.faction.clientportal.exception.InvalidCredentialsException;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private PasswordEncoder passwordEncoder;
    
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
            .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));
        
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid username or password");
        }
        
        String token = jwtTokenProvider.generateToken(user);
        
        return LoginResponse.builder()
            .token(token)
            .user(UserDto.fromEntity(user))
            .build();
    }
}
```

### UserService.java

```java
// src/main/java/com/faction/clientportal/service/UserService.java
package com.faction.clientportal.service;

import com.faction.clientportal.dto.CreateUserRequest;
import com.faction.clientportal.dto.UpdateUserRequest;
import com.faction.clientportal.dto.UserDto;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserService {
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private RoleRepository roleRepository;
    
    @Autowired
    private PasswordEncoder passwordEncoder;
    
    public Page<UserDto> getAllUsers(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return userRepository.findAll(pageable).map(UserDto::fromEntity);
    }
    
    public UserDto getUserById(String id) {
        return userRepository.findById(id)
            .map(UserDto::fromEntity)
            .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
    }
    
    public UserDto createUser(CreateUserRequest request) {
        // Validate that roles exist
        List<Role> roles = roleRepository.findAllById(request.getRoleIds());
        if (roles.size() != request.getRoleIds().size()) {
            throw new IllegalArgumentException("One or more roles not found");
        }
        
        // Check for duplicate username/email
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new IllegalArgumentException("Username already exists");
        }
        
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email already exists");
        }
        
        // Create user entity
        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setActive(true);
        user.setTeamId(request.getTeamId());
        user.setOrganizationId(request.getOrganizationId());
        
        // Set permissions from roles
        List<String> permissions = roles.stream()
            .flatMap(role -> role.getPermissions().stream())
            .collect(Collectors.toList());
        user.setPermissions(permissions);
        
        User savedUser = userRepository.save(user);
        return UserDto.fromEntity(savedUser);
    }
    
    public UserDto updateUser(String id, UpdateUserRequest request) {
        User existingUser = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        
        // Check for duplicate username/email (excluding current user)
        if (request.getUsername() != null && !request.getUsername().equals(existingUser.getUsername())) {
            if (userRepository.findByUsername(request.getUsername()).isPresent()) {
                throw new IllegalArgumentException("Username already exists");
            }
            existingUser.setUsername(request.getUsername());
        }
        
        if (request.getEmail() != null && !request.getEmail().equals(existingUser.getEmail())) {
            if (userRepository.findByEmail(request.getEmail()).isPresent()) {
                throw new IllegalArgumentException("Email already exists");
            }
            existingUser.setEmail(request.getEmail());
        }
        
        if (request.getFirstName() != null) {
            existingUser.setFirstName(request.getFirstName());
        }
        
        if (request.getLastName() != null) {
            existingUser.setLastName(request.getLastName());
        }
        
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            existingUser.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }
        
        if (request.getActive() != null) {
            existingUser.setActive(request.getActive());
        }
        
        if (request.getTeamId() != null) {
            existingUser.setTeamId(request.getTeamId());
        }
        
        if (request.getOrganizationId() != null) {
            existingUser.setOrganizationId(request.getOrganizationId());
        }
        
        // Update roles and permissions
        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            List<Role> roles = roleRepository.findAllById(request.getRoleIds());
            if (roles.size() != request.getRoleIds().size()) {
                throw new IllegalArgumentException("One or more roles not found");
            }
            
            List<String> permissions = roles.stream()
                .flatMap(role -> role.getPermissions().stream())
                .collect(Collectors.toList());
            existingUser.setPermissions(permissions);
        }
        
        User updatedUser = userRepository.save(existingUser);
        return UserDto.fromEntity(updatedUser);
    }
    
    public void deleteUser(String id) {
        if (!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("User not found with id: " + id);
        }
        userRepository.deleteById(id);
    }
}
```

### AssessmentService.java

```java
// src/main/java/com/faction/clientportal/service/AssessmentService.java
package com.faction.clientportal.service;

import com.faction.clientportal.dto.CreateAssessmentRequest;
import com.faction.clientportal.dto.UpdateAssessmentRequest;
import com.faction.clientportal.dto.AssessmentDto;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.Team;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AssessmentService {
    
    @Autowired
    private AssessmentRepository assessmentRepository;
    
    @Autowired
    private AssessmentTypeRepository assessmentTypeRepository;
    
    @Autowired
    private ApplicationRepository applicationRepository;
    
    @Autowired
    private TeamRepository teamRepository;
    
    public Page<AssessmentDto> searchAssessments(String name, String status, String applicationId, 
            String assessmentTypeId, String teamId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        
        return assessmentRepository.findAll((root, query, criteriaBuilder) -> {
            List<javax.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            
            if (name != null && !name.isEmpty()) {
                predicates.add(criteriaBuilder.like(
                    criteriaBuilder.lower(root.get("name")), "%" + name.toLowerCase() + "%"));
            }
            
            if (status != null && !status.isEmpty()) {
                predicates.add(criteriaBuilder.equal(root.get("status"), status));
            }
            
            if (applicationId != null && !applicationId.isEmpty()) {
                predicates.add(criteriaBuilder.equal(root.get("applicationId"), applicationId));
            }
            
            if (assessmentTypeId != null && !assessmentTypeId.isEmpty()) {
                predicates.add(criteriaBuilder.equal(root.get("assessmentTypeId"), assessmentTypeId));
            }
            
            if (teamId != null && !teamId.isEmpty()) {
                predicates.add(criteriaBuilder.equal(root.get("teamId"), teamId));
            }
            
            return criteriaBuilder.and(predicates.toArray(new javax.persistence.criteria.Predicate[0]));
        }, pageable).map(AssessmentDto::fromEntity);
    }
    
    public AssessmentDto getAssessmentById(String id) {
        return assessmentRepository.findById(id)
            .map(AssessmentDto::fromEntity)
            .orElseThrow(() -> new ResourceNotFoundException("Assessment not found with id: " + id));
    }
    
    public AssessmentDto createAssessment(CreateAssessmentRequest request) {
        // Validate assessment type exists
        if (request.getAssessmentTypeId() != null && !assessmentTypeRepository.existsById(request.getAssessmentTypeId())) {
            throw new IllegalArgumentException("Assessment type not found with id: " + request.getAssessmentTypeId());
        }
        
        // Validate application exists
        if (request.getApplicationId() != null && !applicationRepository.existsById(request.getApplicationId())) {
            throw new IllegalArgumentException("Application not found with id: " + request.getApplicationId());
        }
        
        // Validate team exists
        if (request.getTeamId() != null && !teamRepository.existsById(request.getTeamId())) {
            throw new IllegalArgumentException("Team not found with id: " + request.getTeamId());
        }
        
        Assessment assessment = new Assessment();
        assessment.setName(request.getName());
        assessment.setDescription(request.getDescription());
        assessment.setStatus(request.getStatus() != null ? request.getStatus() : "planned");
        assessment.setStartDate(request.getStartDate());
        assessment.setEndDate(request.getEndDate());
        assessment.setApplicationId(request.getApplicationId());
        assessment.setAssessmentTypeId(request.getAssessmentTypeId());
        assessment.setTeamId(request.getTeamId());
        assessment.setAssessorIds(request.getAssessorIds() != null ? request.getAssessorIds() : List.of());
        assessment.setCreatedAt(LocalDateTime.now());
        assessment.setUpdatedAt(assessment.getCreatedAt());
        
        Assessment savedAssessment = assessmentRepository.save(assessment);
        return AssessmentDto.fromEntity(savedAssessment);
    }
    
    public AssessmentDto updateAssessment(String id, UpdateAssessmentRequest request) {
        Assessment existingAssessment = assessmentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Assessment not found with id: " + id));
        
        if (request.getName() != null) {
            existingAssessment.setName(request.getName());
        }
        
        if (request.getDescription() != null) {
            existingAssessment.setDescription(request.getDescription());
        }
        
        if (request.getStatus() != null) {
            existingAssessment.setStatus(request.getStatus());
        }
        
        if (request.getStartDate() != null) {
            existingAssessment.setStartDate(request.getStartDate());
        }
        
        if (request.getEndDate() != null) {
            existingAssessment.setEndDate(request.getEndDate());
        }
        
        if (request.getApplicationId() != null) {
            // Validate application exists
            if (!applicationRepository.existsById(request.getApplicationId())) {
                throw new IllegalArgumentException("Application not found with id: " + request.getApplicationId());
            }
            existingAssessment.setApplicationId(request.getApplicationId());
        }
        
        if (request.getAssessmentTypeId() != null) {
            // Validate assessment type exists
            if (!assessmentTypeRepository.existsById(request.getAssessmentTypeId())) {
                throw new IllegalArgumentException("Assessment type not found with id: " + request.getAssessmentTypeId());
            }
            existingAssessment.setAssessmentTypeId(request.getAssessmentTypeId());
        }
        
        if (request.getTeamId() != null) {
            // Validate team exists
            if (!teamRepository.existsById(request.getTeamId())) {
                throw new IllegalArgumentException("Team not found with id: " + request.getTeamId());
            }
            existingAssessment.setTeamId(request.getTeamId());
        }
        
        if (request.getAssessorIds() != null) {
            existingAssessment.setAssessorIds(request.getAssessorIds());
        }
        
        existingAssessment.setUpdatedAt(LocalDateTime.now());
        
        Assessment updatedAssessment = assessmentRepository.save(existingAssessment);
        return AssessmentDto.fromEntity(updatedAssessment);
    }
    
    public void deleteAssessment(String id) {
        if (!assessmentRepository.existsById(id)) {
            throw new ResourceNotFoundException("Assessment not found with id: " + id);
        }
        assessmentRepository.deleteById(id);
    }
}
```

### ReportTemplateService.java

```java
// src/main/java/com/faction/clientportal/service/ReportTemplateService.java
package com.faction.clientportal.service;

import com.faction.clientportal.dto.CreateReportTemplateRequest;
import com.faction.clientportal.dto.UpdateReportTemplateRequest;
import com.faction.clientportal.dto.ReportTemplateDto;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.repository.ReportTemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReportTemplateService {
    
    @Autowired
    private ReportTemplateRepository reportTemplateRepository;
    
    public Page<ReportTemplateDto> getAllReportTemplates(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return reportTemplateRepository.findAll(pageable).map(ReportTemplateDto::fromEntity);
    }
    
    public ReportTemplateDto getReportTemplateById(String id) {
        return reportTemplateRepository.findById(id)
            .map(ReportTemplateDto::fromEntity)
            .orElseThrow(() -> new ResourceNotFoundException("Report template not found with id: " + id));
    }
    
    public List<ReportTemplateDto> getReportTemplatesByType(String type) {
        return reportTemplateRepository.findByType(type).stream()
            .map(ReportTemplateDto::fromEntity)
            .collect(Collectors.toList());
    }
    
    public ReportTemplateDto createReportTemplate(CreateReportTemplateRequest request) {
        ReportTemplate template = new ReportTemplate();
        template.setName(request.getName());
        template.setDescription(request.getDescription());
        template.setContent(request.getContent());
        template.setType(request.getType() != null ? request.getType() : "assessment");
        template.setDefaultTemplate(request.getDefaultTemplate() != null ? request.getDefaultTemplate() : false);
        template.setOrganizationId(request.getOrganizationId());
        template.setCreatedAt(LocalDateTime.now());
        template.setUpdatedAt(template.getCreatedAt());
        
        ReportTemplate savedTemplate = reportTemplateRepository.save(template);
        return ReportTemplateDto.fromEntity(savedTemplate);
    }
    
    public ReportTemplateDto updateReportTemplate(String id, UpdateReportTemplateRequest request) {
        ReportTemplate existingTemplate = reportTemplateRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Report template not found with id: " + id));
        
        if (request.getName() != null) {
            existingTemplate.setName(request.getName());
        }
        
        if (request.getDescription() != null) {
            existingTemplate.setDescription(request.getDescription());
        }
        
        if (request.getContent() != null) {
            existingTemplate.setContent(request.getContent());
        }
        
        if (request.getType() != null) {
            existingTemplate.setType(request.getType());
        }
        
        if (request.getDefaultTemplate() != null) {
            existingTemplate.setDefaultTemplate(request.getDefaultTemplate());
        }
        
        if (request.getOrganizationId() != null) {
            existingTemplate.setOrganizationId(request.getOrganizationId());
        }
        
        existingTemplate.setUpdatedAt(LocalDateTime.now());
        
        ReportTemplate updatedTemplate = reportTemplateRepository.save(existingTemplate);
        return ReportTemplateDto.fromEntity(updatedTemplate);
    }
    
    public void deleteReportTemplate(String id) {
        if (!reportTemplateRepository.existsById(id)) {
            throw new ResourceNotFoundException("Report template not found with id: " + id);
        }
        reportTemplateRepository.deleteById(id);
    }
}
```

## Controller Layer (controller/)

### UserController.java

```java
// src/main/java/com/faction/clientportal/controller/v1/UserController.java
package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.CreateUserRequest;
import com.faction.clientportal.dto.UpdateUserRequest;
import com.faction.clientportal.dto.UserDto;
import com.faction.clientportal.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    
    @Autowired
    private UserService userService;
    
    @GetMapping
    public ResponseEntity<Page<UserDto>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<UserDto> users = userService.getAllUsers(page, size);
        return ResponseEntity.ok(users);
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUserById(@PathVariable String id) {
        UserDto user = userService.getUserById(id);
        return ResponseEntity.ok(user);
    }
    
    @PostMapping
    public ResponseEntity<UserDto> createUser(@RequestBody CreateUserRequest request) {
        UserDto user = userService.createUser(request);
        return ResponseEntity.status(201).body(user);
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<UserDto> updateUser(
            @PathVariable String id, 
            @RequestBody UpdateUserRequest request) {
        UserDto user = userService.updateUser(id, request);
        return ResponseEntity.ok(user);
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteUser(@PathVariable String id) {
        userService.deleteUser(id);
        return ResponseEntity.ok(Map.of("message", "User deleted successfully"));
    }
}
```

### AssessmentController.java

```java
// src/main/java/com/faction/clientportal/controller/v1/AssessmentController.java
package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.CreateAssessmentRequest;
import com.faction.clientportal.dto.UpdateAssessmentRequest;
import com.faction.clientportal.dto.AssessmentDto;
import com.faction.clientportal.service.AssessmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/assessments")
public class AssessmentController {
    
    @Autowired
    private AssessmentService assessmentService;
    
    @GetMapping("/search")
    public ResponseEntity<Page<AssessmentDto>> searchAssessments(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String applicationId,
            @RequestParam(required = false) String assessmentTypeId,
            @RequestParam(required = false) String teamId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<AssessmentDto> assessments = assessmentService.searchAssessments(
            name, status, applicationId, assessmentTypeId, teamId, page, size);
        return ResponseEntity.ok(assessments);
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<AssessmentDto> getAssessmentById(@PathVariable String id) {
        AssessmentDto assessment = assessmentService.getAssessmentById(id);
        return ResponseEntity.ok(assessment);
    }
    
    @PostMapping
    public ResponseEntity<AssessmentDto> createAssessment(@RequestBody CreateAssessmentRequest request) {
        AssessmentDto assessment = assessmentService.createAssessment(request);
        return ResponseEntity.status(201).body(assessment);
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<AssessmentDto> updateAssessment(
            @PathVariable String id, 
            @RequestBody UpdateAssessmentRequest request) {
        AssessmentDto assessment = assessmentService.updateAssessment(id, request);
        return ResponseEntity.ok(assessment);
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteAssessment(@PathVariable String id) {
        assessmentService.deleteAssessment(id);
        return ResponseEntity.ok(Map.of("message", "Assessment deleted successfully"));
    }
}
```

### ReportTemplateController.java

```java
// src/main/java/com/faction/clientportal/controller/v1/ReportTemplateController.java
package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.CreateReportTemplateRequest;
import com.faction.clientportal.dto.UpdateReportTemplateRequest;
import com.faction.clientportal.dto.ReportTemplateDto;
import com.faction.clientportal.service.ReportTemplateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/report-templates")
public class ReportTemplateController {
    
    @Autowired
    private ReportTemplateService reportTemplateService;
    
    @GetMapping
    public ResponseEntity<Page<ReportTemplateDto>> getAllReportTemplates(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<ReportTemplateDto> templates = reportTemplateService.getAllReportTemplates(page, size);
        return ResponseEntity.ok(templates);
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<ReportTemplateDto> getReportTemplateById(@PathVariable String id) {
        ReportTemplateDto template = reportTemplateService.getReportTemplateById(id);
        return ResponseEntity.ok(template);
    }
    
    @GetMapping("/type/{type}")
    public ResponseEntity<List<ReportTemplateDto>> getReportTemplatesByType(
            @PathVariable String type) {
        List<ReportTemplateDto> templates = reportTemplateService.getReportTemplatesByType(type);
        return ResponseEntity.ok(templates);
    }
    
    @PostMapping
    public ResponseEntity<ReportTemplateDto> createReportTemplate(@RequestBody CreateReportTemplateRequest request) {
        ReportTemplateDto template = reportTemplateService.createReportTemplate(request);
        return ResponseEntity.status(201).body(template);
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<ReportTemplateDto> updateReportTemplate(
            @PathVariable String id, 
            @RequestBody UpdateReportTemplateRequest request) {
        ReportTemplateDto template = reportTemplateService.updateReportTemplate(id, request);
        return ResponseEntity.ok(template);
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteReportTemplate(@PathVariable String id) {
        reportTemplateService.deleteReportTemplate(id);
        return ResponseEntity.ok(Map.of("message", "Report template deleted successfully"));
    }
}
```

## Security Layer (security/)

### JwtTokenProvider.java

```java
// src/main/java/com/faction/clientportal/security/JwtTokenProvider.java
package com.faction.clientportal.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtTokenProvider {
    
    @Value("${jwt.secret}")
    private String jwtSecret;
    
    @Value("${jwt.expiration-ms}")
    private Long jwtExpirationMs;
    
    public String generateToken(com.faction.clientportal.model.User user) {
        List<String> authorities = user.getPermissions().stream()
            .map(String::valueOf)
            .collect(Collectors.toList());
        
        return Jwts.builder()
            .setSubject(user.getUsername())
            .claim("authorities", authorities)
            .claim("userId", user.getId())
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
            .signWith(SignatureAlgorithm.HS512, jwtSecret)
            .compact();
    }
    
    public String getUsernameFromToken(String token) {
        Claims claims = Jwts.parser()
            .setSigningKey(jwtSecret)
            .parseClaimsJws(token)
            .getBody();
        
        return claims.getSubject();
    }
    
    public List<String> getAuthoritiesFromToken(String token) {
        Claims claims = Jwts.parser()
            .setSigningKey(jwtSecret)
            .parseClaimsJws(token)
            .getBody();
        
        return (List<String>) claims.get("authorities");
    }
    
    public String getUserIdFromToken(String token) {
        Claims claims = Jwts.parser()
            .setSigningKey(jwtSecret)
            .parseClaimsJws(token)
            .getBody();
        
        return (String) claims.get("userId");
    }
    
    public boolean validateToken(String token) {
        try {
            Jwts.parser().setSigningKey(jwtSecret).parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

### JwtAuthenticationFilter.java

```java
// src/main/java/com/faction/clientportal/security/JwtAuthenticationFilter.java
package com.faction.clientportal.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = getTokenFromRequest(request);
        
        if (token != null && jwtTokenProvider.validateToken(token)) {
            String username = jwtTokenProvider.getUsernameFromToken(token);
            java.util.List<String> authorities = jwtTokenProvider.getAuthoritiesFromToken(token);
            
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username, null, authorities.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList()));
            
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        
        filterChain.doFilter(request, response);
    }
    
    private String getTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
```

## Exception Handling (exception/)

### GlobalExceptionHandler.java

```java
// src/main/java/com/faction/clientportal/exception/GlobalExceptionHandler.java
package com.faction.clientportal.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleResourceNotFound(ResourceNotFoundException ex) {
        Map<String, String> error = new HashMap<>();
        error.put("code", "RESOURCE_NOT_FOUND");
        error.put("message", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }
    
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleInvalidCredentials(InvalidCredentialsException ex) {
        Map<String, String> error = new HashMap<>();
        error.put("code", "INVALID_CREDENTIALS");
        error.put("message", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.UNAUTHORIZED);
    }
    
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException ex) {
        Map<String, String> error = new HashMap<>();
        error.put("code", "INVALID_REQUEST");
        error.put("message", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception ex) {
        Map<String, String> error = new HashMap<>();
        error.put("code", "INTERNAL_SERVER_ERROR");
        error.put("message", "An unexpected error occurred");
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
```

### ResourceNotFoundException.java

```java
// src/main/java/com/faction/clientportal/exception/ResourceNotFoundException.java
package com.faction.clientportal.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
```

### InvalidCredentialsException.java

```java
// src/main/java/com/faction/clientportal/exception/InvalidCredentialsException.java
package com.faction.clientportal.exception;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
```

## API Documentation (Swagger/OpenAPI)

The backend automatically generates comprehensive API documentation via Swagger UI:

- **Access**: http://localhost:8080/swagger-ui/index.html
- **Features**:
  - Interactive API exploration
  - Request/response examples
  - Parameter validation
  - Try-it-out functionality
  - Download OpenAPI specification (JSON)

## Testing Strategy

### Unit Tests (JUnit)

```java
// src/test/java/com/faction/clientportal/service/UserServiceTest.java
package com.faction.clientportal.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class UserServiceTest {
    
    @Autowired
    private UserService userService;
    
    // Test user creation
    @Test
    public void testCreateUser() {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername("testuser");
        request.setEmail("test@example.com");
        request.setFirstName("Test");
        request.setLastName("User");
        request.setPassword("password123");
        
        UserDto user = userService.createUser(request);
        assertNotNull(user.getId());
        assertEquals("testuser", user.getUsername());
    }
}
```

### Integration Tests (Testcontainers)

```java
// src/test/java/com/faction/clientportal/integration/UserIntegrationTest.java
package com.faction.clientportal.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class UserIntegrationTest {
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    @Test
    public void testCreateUser() {
        // First, login to get token
        ResponseEntity<String> loginResponse = restTemplate.postForEntity(
            "/api/v1/auth/login", 
            new LoginRequest("admin", "admin123"), 
            String.class);
        
        String token = loginResponse.getBody();
        
        // Create user with valid token
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername("testuser");
        request.setEmail("test@example.com");
        request.setFirstName("Test");
        request.setLastName("User");
        request.setPassword("password123");
        
        HttpEntity<CreateUserRequest> entity = new HttpEntity<>(request, headers);
        ResponseEntity<UserDto> response = restTemplate.postForEntity(
            "/api/v1/users", entity, UserDto.class);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody().getId());
    }
}
```

## Deployment Configuration

### Dockerfile

```dockerfile
# src/main/docker/Dockerfile
FROM openjdk:17-jre-slim

WORKDIR /app

COPY target/backend-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

### application.properties

```properties
# Database configuration
spring.data.mongodb.uri=mongodb://localhost:27017/faction

# JWT Configuration
jwt.secret=your-super-secret-jwt-key-change-in-production
jwt.expiration-ms=86400000 # 24 hours

# Server configuration
server.port=8080

# Swagger/OpenAPI
springdoc.api-docs.path=/v3/api-docs
springdoc.swagger-ui.path=/swagger-ui/index.html

# Logging
logging.level.com.faction.clientportal=INFO
```

## Performance Considerations

### Database Optimization
- MongoDB indexing on frequently queried fields:
  - `username` (unique index)
  - `email` (unique index)
  - `teamId`, `organizationId`
  - `status`, `createdAt`
- Connection pooling with HikariCP
- Efficient query patterns avoiding N+1 queries

### Caching Strategy
- Redis caching for frequently accessed data:
  - User permissions
  - Role definitions
  - Application configurations
- Cache invalidation on data updates

## Security Considerations

### Authentication
- JWT tokens with strong secret key (environment variable)
- Token expiration set to 24 hours
- Refresh token mechanism for long-lived sessions
- HTTPS enforcement in production
- **API keys** for programmatic access — opaque `sk_fac_…` bearer tokens authenticated by `ApiKeyAuthenticationFilter` (ahead of the JWT filter), with authorities resolved live per request. See [API Keys](./api-keys.md).

### Authorization
- Fine-grained permission system at API level
- Permission validation on every request
- Data filtering based on user permissions
- Role-based access control with inheritance

### Input Validation
- Server-side validation of all inputs
- Sanitization of user input to prevent injection attacks
- Rate limiting on authentication endpoints
- CORS configuration to allow only trusted origins

## Monitoring & Logging

### Structured Logging
- JSON format for log entries
- Standardized fields: timestamp, level, message, context
- Log correlation IDs for request tracing

### Metrics Collection
- Prometheus metrics endpoint at `/actuator/prometheus`
- Key metrics:
  - API response times
  - Error rates by endpoint
  - Database query performance
  - Memory and CPU usage

## Future Enhancements

### Backend Roadmap
1. Implement audit logging for all data changes
2. Add bulk operations support (bulk create/update/delete)
3. Enhance search capabilities with Elasticsearch integration
4. Add webhook notifications for key events
5. Implement rate limiting and throttling
6. Add caching layer with Redis
7. Support for GraphQL API alongside REST
8. Implement background job processing with RabbitMQ/Kafka
9. Add data export in additional formats (PDF, Excel)
10. Implement multi-tenancy support for enterprise customers

## Maintenance Guidelines

### Code Quality
- Follow existing code patterns and conventions
- Write unit tests for new functionality (target 85%+ coverage)
- Keep services focused on single responsibilities
- Use descriptive variable names
- Document complex logic with comments

### Dependencies
- Regularly update dependencies to patch security vulnerabilities
- Use `mvn dependency-check:check` to identify vulnerabilities
- Pin versions in pom.xml for reproducible builds

### Deployment
- Build production JAR with Maven profile:
  ```bash
  mvn clean package -Pprod
  ```
- Deploy as a Spring Boot application with environment-specific configuration
- Use Docker containers for consistent deployment across environments
- Configure reverse proxy (Nginx) for SSL termination and load balancing

---
This documentation provides a comprehensive overview of the FACTION Client Portal backend architecture and services. For detailed implementation information on specific components, refer to the individual service files.