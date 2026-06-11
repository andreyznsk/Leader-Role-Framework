package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Incident;
import ru.andreyz.memoryservice.repository.IncidentRepository;

import java.time.Instant;
import java.util.List;

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
        Incident incident = findById(id);
        Incident resolved = new Incident(incident.id(), incident.title(), incident.severity(),
                "RESOLVED", incident.description(), rootCause, actionItems,
                incident.startedAt(), Instant.now(), incident.createdAt());
        return incidentRepository.save(resolved);
    }

    public Incident update(Long id, Incident updated) {
        findById(id);
        return incidentRepository.save(updated);
    }

    public List<Incident> findByStatus(String status) {
        return incidentRepository.findByStatus(status);
    }

    public List<Incident> findAll() {
        return (List<Incident>) incidentRepository.findAll();
    }

    public Incident findById(Long id) {
        return incidentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Incident not found: " + id));
    }
}
