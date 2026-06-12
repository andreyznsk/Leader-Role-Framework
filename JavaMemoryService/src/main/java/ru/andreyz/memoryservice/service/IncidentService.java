package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Incident;
import ru.andreyz.memoryservice.repository.IncidentRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class IncidentService {

    private final IncidentRepository incidentRepository;

    public IncidentService(IncidentRepository incidentRepository) {
        this.incidentRepository = incidentRepository;
    }

    public Incident create(String title, String severity, String description) {
        Incident incident = new Incident(null, title, severity, "OPEN",
                description, null, null,
                Instant.now(), null, Instant.now());
        return incidentRepository.save(incident);
    }

    public Incident resolve(Long id, String rootCause, String actionItems) {
        Incident incident = incidentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Incident not found: " + id));
        Incident resolved = new Incident(incident.id(), incident.title(), incident.severity(),
                "RESOLVED", incident.description(), rootCause, actionItems,
                incident.startedAt(), Instant.now(), incident.createdAt());
        return incidentRepository.save(resolved);
    }

    public Incident update(Long id, Incident incoming) {
        Incident existing = incidentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Incident not found: " + id));
        Incident updated = new Incident(
                existing.id(),
                incoming.title() != null ? incoming.title() : existing.title(),
                incoming.severity() != null ? incoming.severity() : existing.severity(),
                incoming.status() != null ? incoming.status() : existing.status(),
                incoming.description() != null ? incoming.description() : existing.description(),
                incoming.rootCause() != null ? incoming.rootCause() : existing.rootCause(),
                incoming.actionItems() != null ? incoming.actionItems() : existing.actionItems(),
                existing.startedAt(),
                existing.resolvedAt(),
                existing.createdAt());
        return incidentRepository.save(updated);
    }

    public void delete(Long id) {
        Incident existing = incidentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Incident not found: " + id));
        Incident closed = new Incident(
                existing.id(), existing.title(), existing.severity(),
                "CLOSED", existing.description(), existing.rootCause(), existing.actionItems(),
                existing.startedAt(), Instant.now(), existing.createdAt());
        incidentRepository.save(closed);
    }

    public List<Incident> findByStatus(String status) {
        return incidentRepository.findByStatus(status);
    }

    public List<Incident> findAll() {
        return (List<Incident>) incidentRepository.findAll();
    }

    public Optional<Incident> findById(Long id) {
        return incidentRepository.findById(id);
    }
}
