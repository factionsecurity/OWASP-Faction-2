package com.faction.clientportal.repository;

import com.faction.clientportal.model.MentionTargetType;
import com.faction.clientportal.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, String> {
    List<Notification> findByUsernameOrderByCreatedAtDesc(String username);
    long countByUsernameAndReadFalse(String username);
    Optional<Notification> findByIdAndUsername(String id, String username);
    List<Notification> findByUsernameAndReadFalse(String username);
    void deleteByUsername(String username);
    List<Notification> findByUsernameAndTypeInOrderByCreatedAtDesc(String username, List<String> types);
    long countByUsernameAndTypeInAndReadFalse(String username, List<String> types);
    void deleteByUsernameAndTypeIn(String username, List<String> types);
    void deleteByUsernameAndTypeInAndTargetType(String username, List<String> types, MentionTargetType targetType);
    void deleteByUsernameAndTypeInAndTargetTypeIsNull(String username, List<String> types);

    // ── Section-scoped mention queries ─────────────────────────────────────────
    // Untargeted rows pass every filter: the page shows them under "Other", so excluding
    // them would leave an unread count the reader has no way to clear. `visible` is never
    // empty at these call sites — an empty section set uses the IsNull variants above,
    // because `in ()` is not valid SQL.

    @Query("select n from Notification n where n.username = :username and n.type in :types "
            + "and (n.targetType is null or n.targetType in :visible) order by n.createdAt desc")
    List<Notification> findMentionsInSections(@Param("username") String username,
                                              @Param("types") List<String> types,
                                              @Param("visible") Collection<MentionTargetType> visible);

    @Query("select count(n) from Notification n where n.username = :username and n.type in :types "
            + "and n.read = false and (n.targetType is null or n.targetType in :visible)")
    long countUnreadMentionsInSections(@Param("username") String username,
                                       @Param("types") List<String> types,
                                       @Param("visible") Collection<MentionTargetType> visible);

    @Modifying
    @Query("delete from Notification n where n.username = :username and n.type in :types "
            + "and (n.targetType is null or n.targetType in :visible)")
    void deleteMentionsInSections(@Param("username") String username,
                                  @Param("types") List<String> types,
                                  @Param("visible") Collection<MentionTargetType> visible);

    List<Notification> findByUsernameAndTypeInAndTargetTypeIsNullOrderByCreatedAtDesc(
            String username, List<String> types);

    long countByUsernameAndTypeInAndTargetTypeIsNullAndReadFalse(String username, List<String> types);
}
