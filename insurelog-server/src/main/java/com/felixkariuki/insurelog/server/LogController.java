package com.felixkariuki.insurelog.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.felixkariuki.insurelog.core.InsuranceLogger;
import com.felixkariuki.insurelog.core.Json;
import com.felixkariuki.insurelog.core.LogStore;
import com.felixkariuki.insurelog.model.Category;
import com.felixkariuki.insurelog.model.Level;
import com.felixkariuki.insurelog.model.LogEvent;
import com.felixkariuki.insurelog.model.LogQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api")
@Tag(name = "Logs", description = "Ingest, search and export insurance events")
public class LogController {

    static final int MAX_BATCH = 500;

    private final InsuranceLogger logger;
    private final LogStore store;

    public LogController(InsuranceLogger logger, LogStore store) {
        this.logger = logger;
        this.store = store;
    }

    @PostMapping(value = "/logs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Log one event or an array of up to 500 events",
            description = "Returns 201 if all events were accepted, 207 if some were rejected, 400 if none were.")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody JsonNode body) {
        List<LogEvent> events;
        try {
            events = body.isArray()
                    ? Json.MAPPER.convertValue(body, new TypeReference<List<LogEvent>>() { })
                    : List.of(Json.MAPPER.treeToValue(body, LogEvent.class));
        } catch (Exception e) {
            logger.metrics().rejected("bad_json");
            throw new IllegalArgumentException("Body must be a JSON event or an array of events");
        }
        if (events.size() > MAX_BATCH) {
            logger.metrics().rejected("batch_too_large");
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "At most " + MAX_BATCH + " events per request"));
        }

        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        logger.metrics().ingestTimer().record(() -> {
            for (int i = 0; i < events.size(); i++) {
                try {
                    ids.add(logger.log(events.get(i)).id());
                } catch (InsuranceLogger.InvalidEventException e) {
                    errors.add(Map.of("index", i, "errors", e.errors()));
                }
            }
        });

        HttpStatus status = errors.isEmpty() ? HttpStatus.CREATED
                : ids.isEmpty() ? HttpStatus.BAD_REQUEST : HttpStatus.MULTI_STATUS;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", ids.size());
        result.put("rejected", errors.size());
        result.put("ids", ids);
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/logs")
    @Operation(summary = "Search events, newest first",
            description = "Filters: level (minimum), category, eventType, service, correlationId, policyNumber, claimId, customerId, since, until, limit")
    public List<LogEvent> search(@Parameter(hidden = true) @RequestParam Map<String, String> params) {
        return store.query(LogQuery.fromParams(params));
    }

    @GetMapping("/logs/trace/{correlationId}")
    @Operation(summary = "Follow one customer journey across every microservice, oldest first")
    public List<LogEvent> trace(@PathVariable String correlationId) {
        Map<String, String> params = new HashMap<>();
        params.put("correlationId", correlationId);
        params.put("limit", String.valueOf(LogQuery.MAX_LIMIT));
        List<LogEvent> events = new ArrayList<>(store.query(LogQuery.fromParams(params)));
        Collections.reverse(events); // arrival order, so events with equal timestamps stay in sequence
        events.sort(Comparator.comparing(LogEvent::timestamp));
        return events;
    }

    @GetMapping("/logs/export")
    @Operation(summary = "Download events as CSV (default) or JSON, using the same filters as search")
    public ResponseEntity<String> export(@Parameter(hidden = true) @RequestParam Map<String, String> params) throws Exception {
        Map<String, String> p = new HashMap<>(params);
        String format = p.getOrDefault("format", "csv").toLowerCase();
        p.putIfAbsent("limit", String.valueOf(LogQuery.MAX_LIMIT));
        List<LogEvent> events = store.query(LogQuery.fromParams(p));
        String stamp = Instant.now().toString().replace(":", "-");
        return switch (format) {
            case "json" -> ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"insurance-logs-" + stamp + ".json\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Json.MAPPER.writeValueAsString(events));
            case "csv" -> ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"insurance-logs-" + stamp + ".csv\"")
                    .contentType(new MediaType("text", "csv"))
                    .body(CsvExporter.toCsv(events));
            default -> throw new IllegalArgumentException("format must be csv or json");
        };
    }

    @GetMapping("/stats")
    @Operation(summary = "Event counts by service, category and level")
    public Map<String, Object> stats() {
        List<LogEvent> all = store.query(LogQuery.all(Integer.MAX_VALUE));
        Map<Category, Integer> byCategory = new EnumMap<>(Category.class);
        Map<Level, Integer> byLevel = new EnumMap<>(Level.class);
        Map<String, Integer> byService = new TreeMap<>();
        for (LogEvent e : all) {
            byCategory.merge(e.category(), 1, Integer::sum);
            byLevel.merge(e.level(), 1, Integer::sum);
            byService.merge(e.service() == null ? "unknown" : e.service(), 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventsInMemory", all.size());
        out.put("byService", byService);
        out.put("byCategory", byCategory);
        out.put("byLevel", byLevel);
        out.put("oldest", all.isEmpty() ? null : all.get(all.size() - 1).timestamp());
        out.put("newest", all.isEmpty() ? null : all.get(0).timestamp());
        return out;
    }
}
