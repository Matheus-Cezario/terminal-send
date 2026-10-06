package dev.terminalsend.server.connection;

import dev.terminalsend.protocol.rest.ConnectionDtos.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConnectionRepository extends JpaRepository<Connection, UUID> {

    @Query("""
            select c from Connection c
            where (c.requesterId = :a and c.addresseeId = :b) or (c.requesterId = :b and c.addresseeId = :a)
            """)
    Optional<Connection> findBetween(UUID a, UUID b);

    @Query("select c from Connection c where c.requesterId = :userId or c.addresseeId = :userId order by c.createdAt")
    List<Connection> findAllInvolving(UUID userId);

    @Query("""
            select case when c.requesterId = :userId then c.addresseeId else c.requesterId end
            from Connection c
            where (c.requesterId = :userId or c.addresseeId = :userId)
              and c.status = dev.terminalsend.protocol.rest.ConnectionDtos.Status.ACCEPTED
            """)
    List<UUID> findAcceptedPeerIds(UUID userId);

    default boolean areConnected(UUID a, UUID b) {
        return findBetween(a, b).filter(c -> c.getStatus() == Status.ACCEPTED).isPresent();
    }
}
