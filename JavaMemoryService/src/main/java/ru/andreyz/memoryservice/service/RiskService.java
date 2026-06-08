package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Risk;
import ru.andreyz.memoryservice.repository.RiskRepository;

import java.time.Instant;
import java.util.List;

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

    public Risk update(Long id, Risk updated) {
        findById(id);
        return riskRepository.save(updated);
    }

    public List<Risk> findByStatus(String status) {
        return riskRepository.findByStatus(status);
    }

    public List<Risk> findAll() {
        return (List<Risk>) riskRepository.findAll();
    }

    public Risk findById(Long id) {
        return riskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Risk not found: " + id));
    }
}
