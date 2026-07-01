package ru.andreyz.memoryservice.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.memoryservice.domain.IntakeItem;

import java.util.UUID;

public interface IntakeItemRepository extends CrudRepository<IntakeItem, UUID> {
}
