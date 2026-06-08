package ru.andreyz.memoryservice.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.Incident;
import ru.andreyz.memoryservice.dto.ResolveIncidentRequest;
import ru.andreyz.memoryservice.service.IncidentService;

import java.util.List;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @GetMapping
    public ResponseEntity<List<Incident>> getIncidents(@RequestParam(required = false) String status) {
        List<Incident> incidents = status != null
                ? incidentService.findByStatus(status)
                : incidentService.findAll();
        return ResponseEntity.ok(incidents);
    }

    @PostMapping
    public ResponseEntity<Incident> create(@RequestBody Incident incident) {
        return ResponseEntity.ok(
                incidentService.create(incident.title(), incident.severity(), incident.description()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Incident> update(@PathVariable Long id, @RequestBody Incident incident) {
        return ResponseEntity.ok(incidentService.update(id, incident));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<Incident> resolve(@PathVariable Long id,
                                            @RequestBody ResolveIncidentRequest req) {
        return ResponseEntity.ok(incidentService.resolve(id, req.rootCause(), req.actionItems()));
    }
}
