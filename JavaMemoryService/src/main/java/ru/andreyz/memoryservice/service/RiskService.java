package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Risk;
import ru.andreyz.memoryservice.repository.RiskRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class RiskService {

    private final RiskRepository riskRepository;

    public RiskService(RiskRepository riskRepository) {
        this.riskRepository = riskRepository;
    }

    public Risk create(String title, String description, String probability, String impact) {
        Risk risk = new Risk(null, title, description,
                probability != null ? probability : "MEDIUM",
                impact != null ? impact : "MEDIUM",
                "OPEN", null,
                Instant.now(), Instant.now());
        return riskRepository.save(risk);
    }

    public Risk update(Long id, Risk incoming) {
        Risk existing = riskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Risk not found: " + id));
        Risk updated = new Risk(
                existing.id(),
                incoming.title() != null ? incoming.title() : existing.title(),
                incoming.description() != null ? incoming.description() : existing.description(),
                incoming.probability() != null ? incoming.probability() : existing.probability(),
                incoming.impact() != null ? incoming.impact() : existing.impact(),
                incoming.status() != null ? incoming.status() : existing.status(),
                incoming.mitigation() != null ? incoming.mitigation() : existing.mitigation(),
                existing.createdAt(),
                Instant.now());
        return riskRepository.save(updated);
    }

    public List<Risk> findByStatus(String status) {
        return riskRepository.findByStatus(status);
    }

    public List<Risk> findAll() {
        return (List<Risk>) riskRepository.findAll();
    }

    public Optional<Risk> findById(Long id) {
        return riskRepository.findById(id);
    }

    public void delete(Long id) {
        Risk existing = riskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Risk not found: " + id));
        Risk closed = new Risk(
                existing.id(), existing.title(), existing.description(),
                existing.probability(), existing.impact(),
                "CLOSED", existing.mitigation(), existing.createdAt(), Instant.now());
        riskRepository.save(closed);
    }
}
