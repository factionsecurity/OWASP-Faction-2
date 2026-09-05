package com.faction.clientportal.repository;

import com.faction.clientportal.model.ApplicationConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationConnectionRepository extends JpaRepository<ApplicationConnection, String> {

    // Find all connections from a source application
    List<ApplicationConnection> findBySourceApplicationId(String sourceApplicationId);

    // Find all connections to a target application
    List<ApplicationConnection> findByTargetApplicationId(String targetApplicationId);

    // Find all connections for an application (both incoming and outgoing)
    List<ApplicationConnection> findBySourceApplicationIdOrTargetApplicationId(
            String sourceApplicationId,
            String targetApplicationId
    );

    // Check if connection already exists
    Optional<ApplicationConnection> findBySourceApplicationIdAndTargetApplicationId(
            String sourceApplicationId,
            String targetApplicationId
    );

}
