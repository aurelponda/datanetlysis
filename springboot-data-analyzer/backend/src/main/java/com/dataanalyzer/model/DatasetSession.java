package com.dataanalyzer.model;

import java.time.Instant;

public final class DatasetSession {
    private final String id;
    private final String fileName;
    private final long fileSize;
    private final String fileType;
    private final Instant createdAt;
    private final DataTable original;
    private volatile DataTable current;
    private volatile Instant lastAccess;

    public DatasetSession(String id, String fileName, long fileSize, String fileType, DataTable table) {
        this.id = id;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.fileType = fileType;
        this.original = table.copy();
        this.current = table.copy();
        this.createdAt = Instant.now();
        this.lastAccess = createdAt;
    }

    public synchronized DataTable current() { touch(); return current.copy(); }
    public synchronized void update(DataTable table) { this.current = table.copy(); touch(); }
    public synchronized void reset() { this.current = original.copy(); touch(); }
    public synchronized DataTable original() { touch(); return original.copy(); }
    public void touch() { lastAccess = Instant.now(); }
    public String id() { return id; }
    public String fileName() { return fileName; }
    public long fileSize() { return fileSize; }
    public String fileType() { return fileType; }
    public Instant createdAt() { return createdAt; }
    public Instant lastAccess() { return lastAccess; }
}
