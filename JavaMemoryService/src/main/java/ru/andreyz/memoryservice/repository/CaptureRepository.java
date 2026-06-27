package ru.andreyz.memoryservice.repository;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import ru.andreyz.memoryservice.domain.Capture;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CaptureRepository extends CrudRepository<Capture, Long> {

    List<Capture> findByStatus(String status);

    @Query("SELECT * FROM captures WHERE status = :status AND captured_at >= :from AND captured_at < :to ORDER BY captured_at")
    List<Capture> findByStatusAndDay(@Param("status") String status,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to);

    @Query("SELECT * FROM captures WHERE captured_at >= :from AND captured_at < :to ORDER BY captured_at")
    List<Capture> findByDay(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT * FROM captures ORDER BY captured_at DESC LIMIT 20")
    List<Capture> findRecent();

    Optional<Capture> findBySourceAndSourceId(String source, String sourceId);
}
