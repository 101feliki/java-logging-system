package com.felixkariuki.insurelog.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.felixkariuki.insurelog.model.LogEvent;
import com.felixkariuki.insurelog.model.LogQuery;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps recent events in memory for fast searching and appends every event to a
 * daily JSON-lines file ({@code insurance-YYYY-MM-DD.log}). Files older than the
 * retention period are deleted automatically.
 */
public class LogStore implements AutoCloseable {

    private static final Pattern FILE_NAME = Pattern.compile("insurance-(\\d{4}-\\d{2}-\\d{2})\\.log");

    private final Path directory;
    private final int memoryCapacity;
    private final int retentionDays;
    private final Clock clock;
    private final Deque<LogEvent> recent = new ArrayDeque<>();

    private BufferedWriter writer;
    private LocalDate currentDate;

    public LogStore(Path directory, int memoryCapacity, int retentionDays) {
        this(directory, memoryCapacity, retentionDays, Clock.systemUTC());
    }

    LogStore(Path directory, int memoryCapacity, int retentionDays, Clock clock) {
        this.directory = directory;
        this.memoryCapacity = memoryCapacity;
        this.retentionDays = retentionDays;
        this.clock = clock;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create log directory " + directory, e);
        }
        loadToday();
        deleteExpiredFiles();
    }

    public synchronized void append(LogEvent event) {
        recent.addLast(event);
        while (recent.size() > memoryCapacity) {
            recent.removeFirst();
        }
        try {
            rotateIfNeeded();
            writer.write(Json.MAPPER.writeValueAsString(event));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write log event", e);
        }
    }

    /** Newest events first. */
    public synchronized List<LogEvent> query(LogQuery q) {
        List<LogEvent> out = new ArrayList<>();
        Iterator<LogEvent> it = recent.descendingIterator();
        while (it.hasNext() && out.size() < q.limit()) {
            LogEvent e = it.next();
            if (q.matches(e)) {
                out.add(e);
            }
        }
        return out;
    }

    public synchronized int size() {
        return recent.size();
    }

    public Path directory() {
        return directory;
    }

    Path fileFor(LocalDate date) {
        return directory.resolve("insurance-" + date + ".log");
    }

    private void rotateIfNeeded() throws IOException {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (writer == null || !today.equals(currentDate)) {
            if (writer != null) {
                writer.close();
                deleteExpiredFiles();
            }
            currentDate = today;
            writer = Files.newBufferedWriter(fileFor(today), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    /** Reloads today's file on startup so recent events survive a restart. */
    private void loadToday() {
        Path today = fileFor(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        if (!Files.exists(today)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(today, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    recent.addLast(Json.MAPPER.readValue(line, LogEvent.class));
                    if (recent.size() > memoryCapacity) {
                        recent.removeFirst();
                    }
                } catch (JsonProcessingException ignored) {
                    // skip a corrupt line rather than refusing to start
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + today, e);
        }
    }

    synchronized int deleteExpiredFiles() {
        if (retentionDays <= 0) {
            return 0;
        }
        LocalDate cutoff = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(retentionDays);
        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "insurance-*.log")) {
            for (Path f : files) {
                Matcher m = FILE_NAME.matcher(f.getFileName().toString());
                if (m.matches() && LocalDate.parse(m.group(1)).isBefore(cutoff)) {
                    Files.deleteIfExists(f);
                    deleted++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not clean up old log files", e);
        }
        return deleted;
    }

    @Override
    public synchronized void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }
}
