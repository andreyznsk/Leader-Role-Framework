package ru.andreyz.memoryservice.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.memoryservice.domain.Risk;

import java.util.List;

public interface RiskRepository extends CrudRepository<Risk, Long> {
    List<Risk> findByStatus(String status);
}
