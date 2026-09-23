package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.UserEntity;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    @Override
    @EntityGraph(attributePaths = "roles")
    Page<UserEntity> findAll(Pageable pageable);
    /** Derived-query equivalent of Prisma's where: { email }. */
    Optional<UserEntity> findByEmail(String email);
    Optional<UserEntity> findByUsernameIgnoreCase(String username);
    Optional<UserEntity> findById(UUID id);

    @Query(value = "select exists (select 1 from users where lower(email) = lower(:email) and id <> :id)",
            nativeQuery = true)
    boolean existsByEmailIgnoringCaseAndIdNotIncludingDeleted(@Param("email") String email,
                                                               @Param("id") UUID id);

    long countByRoleInAndStatus(Collection<com.utewedding.app.domain.UserRole> roles,
                                com.utewedding.app.domain.UserStatus status);

    @Query("select count(u) from UserEntity u "
            + "where u.id <> :targetId and u.role = com.utewedding.app.domain.UserRole.SUPER_ADMIN "
            + "and u.status = com.utewedding.app.domain.UserStatus.ACTIVE and u.deletedAt is null")
    long countActiveSuperAdminsExcept(@Param("targetId") UUID targetId);

    @Query("select u from UserEntity u where u.role in :legacyRoles")
    List<UserEntity> findOperationsUsers(
            @Param("legacyRoles") Collection<com.utewedding.app.domain.UserRole> legacyRoles);

    @Query("select u from UserEntity u where u.role in :roles "
            + "and u.status = com.utewedding.app.domain.UserStatus.ACTIVE and u.deletedAt is null order by u.id")
    List<UserEntity> findActiveSupportUsers(@Param("roles") Collection<com.utewedding.app.domain.UserRole> roles);

    @Query("select u from UserEntity u "
            + "where u.status = com.utewedding.app.domain.UserStatus.ACTIVE "
            + "and u.role = com.utewedding.app.domain.UserRole.SHIPPER "
            + "order by u.displayName asc")
    List<UserEntity> findActiveShippers();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u "
            + "where u.role = com.utewedding.app.domain.UserRole.SUPER_ADMIN "
            + "and u.status = com.utewedding.app.domain.UserStatus.ACTIVE and u.deletedAt is null "
            + "order by u.id")
    List<UserEntity> lockActiveSuperAdmins();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u where u.id = :id")
    Optional<UserEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = "select * from users where id = :id", nativeQuery = true)
    Optional<UserEntity> findByIdIncludingDeleted(@org.springframework.data.repository.query.Param("id") UUID id);

    @Query(value = "select cast(u.id as varchar) as id, u.email as email, u.display_name as \"displayName\", "
            + "u.role as role, u.status as status, u.created_at as \"createdAt\", "
            + "u.updated_at as \"updatedAt\", u.deleted_at as \"deletedAt\" "
            + "from users u "
            + "where (coalesce(:search, '') = '' or lower(u.display_name) like lower(concat('%', coalesce(:search, ''), '%')) "
            + "or lower(u.email) like lower(concat('%', coalesce(:search, ''), '%')) "
            + "or lower(cast(u.id as varchar)) like lower(concat('%', coalesce(:search, ''), '%'))) "
            + "and (coalesce(:role, '') = '' or cast(u.role as text) = coalesce(:role, '')) "
            + "and (coalesce(:status, '') = '' or cast(u.status as text) = coalesce(:status, '')) "
            + "and (:accountState = 'ALL' or (:accountState = 'ACTIVE' and u.deleted_at is null "
            + "and u.status <> 'DELETED') or (:accountState = 'DELETED' "
            + "and (u.deleted_at is not null or u.status = 'DELETED'))) "
            + "order by u.created_at desc, u.id asc",
            countQuery = "select count(*) from users u "
                    + "where (coalesce(:search, '') = '' or lower(u.display_name) like lower(concat('%', coalesce(:search, ''), '%')) "
                    + "or lower(u.email) like lower(concat('%', coalesce(:search, ''), '%')) "
                    + "or lower(cast(u.id as varchar)) like lower(concat('%', coalesce(:search, ''), '%'))) "
                    + "and (coalesce(:role, '') = '' or cast(u.role as text) = coalesce(:role, '')) "
                    + "and (coalesce(:status, '') = '' or cast(u.status as text) = coalesce(:status, '')) "
                    + "and (:accountState = 'ALL' or (:accountState = 'ACTIVE' and u.deleted_at is null "
                    + "and u.status <> 'DELETED') or (:accountState = 'DELETED' "
                    + "and (u.deleted_at is not null or u.status = 'DELETED')))",
            nativeQuery = true)
    Page<AdminUserSearchRow> searchForAdminUsers(
            @Param("search") String search,
            @Param("role") String role,
            @Param("status") String status,
            @Param("accountState") String accountState,
            Pageable pageable);
}
