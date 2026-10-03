package com.dataanalyzer.service;

import com.dataanalyzer.model.DatasetSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DatasetStore {
    private final Map<String, DatasetSession> sessions = new ConcurrentHashMap<>();
    private final long ttlMinutes;

    public DatasetStore(@Value("${app.session-ttl-minutes:30}") long ttlMinutes) {
        this.ttlMinutes = Math.max(1, ttlMinutes);
    }

    public DatasetSession create(String name, long size, String type, com.dataanalyzer.model.DataTable table) {
        String id = UUID.randomUUID().toString();
        DatasetSession session = new DatasetSession(id, name, size, type, table);
        sessions.put(id, session);
        return session;
    }

    public DatasetSession require(String id) {
        DatasetSession session = sessions.get(id);
        if (session == null) throw new DatasetNotFoundException();
        session.touch();
        return session;
    }

    public void delete(String id) {
        if (sessions.remove(id) == null) throw new DatasetNotFoundException();
    }

    @Scheduled(fixedDelay = 60000)
    void expireIdleSessions() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(ttlMinutes));
        sessions.entrySet().removeIf(entry -> entry.getValue().lastAccess().isBefore(cutoff));
    }

    public static final class DatasetNotFoundException extends RuntimeException { }
}
