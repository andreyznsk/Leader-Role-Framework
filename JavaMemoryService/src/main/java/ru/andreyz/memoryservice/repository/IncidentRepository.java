package ru.andreyz.memoryservice.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.memoryservice.domain.Incident;

import java.util.List;

public interface IncidentRepository extends CrudRepository<Incident, Long> {
    List<Incident> findByStatus(String status);
}
