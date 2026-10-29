package org.heavenlyweiner.donatenotify.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.heavenlyweiner.donatenotify.logic.Threshold;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** История в SQLite на прокси. Уникальность (uuid, группа, срок, порог) обеспечивает первичный ключ. */
public final class SqliteHistoryStore implements HistoryStore {

    private static final int SCHEMA_VERSION = 1;
    private static final int BUSY_TIMEOUT_MILLIS = 5000;

    private final Connection db;

    public SqliteHistoryStore(Path file) throws StorageException {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            SQLiteConfig config = new SQLiteConfig();
            config.setJournalMode(SQLiteConfig.JournalMode.WAL);
            config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
            config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
            SQLiteDataSource source = new SQLiteDataSource(config);
            source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
            db = source.getConnection();
            try {
                schema();
            } catch (SQLException e) {
                db.close();
                throw e;
            }
        } catch (SQLException | IOException e) {
            throw new StorageException("не удалось открыть " + file + ": " + e.getMessage(), e);
        }
    }

    private void schema() throws SQLException {
        try (Statement statement = db.createStatement()) {
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                if (version.next() && version.getInt(1) > SCHEMA_VERSION) {
                    throw new SQLException("схема history.db новее поддерживаемой: " + version.getInt(1));
                }
            }
            statement.execute("CREATE TABLE IF NOT EXISTS notifications("
                    + "uuid TEXT NOT NULL, grp TEXT NOT NULL, expiry INTEGER NOT NULL, threshold INTEGER NOT NULL, "
                    + "sent_at INTEGER NOT NULL, PRIMARY KEY(uuid, grp, expiry, threshold))");
            statement.execute("CREATE TABLE IF NOT EXISTS tracked("
                    + "uuid TEXT NOT NULL, grp TEXT NOT NULL, expiry INTEGER NOT NULL, first_seen INTEGER NOT NULL, "
                    + "last_seen INTEGER NOT NULL, state TEXT NOT NULL, PRIMARY KEY(uuid, grp, expiry))");
            statement.execute("CREATE INDEX IF NOT EXISTS tracked_player ON tracked(uuid, grp, state)");
            statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
        }
    }

    @Override
    public synchronized boolean claim(UUID uuid, String group, Instant expiry, Threshold threshold, Instant now)
            throws StorageException {
        try (PreparedStatement statement = db.prepareStatement(
                "INSERT OR IGNORE INTO notifications(uuid, grp, expiry, threshold, sent_at) VALUES(?,?,?,?,?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, group);
            statement.setLong(3, expiry.getEpochSecond());
            statement.setLong(4, threshold.seconds());
            statement.setLong(5, now.getEpochSecond());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw wrap("запись уведомления", e);
        }
    }

    @Override
    public synchronized void release(UUID uuid, String group, Instant expiry, Threshold threshold) throws StorageException {
        try (PreparedStatement statement = db.prepareStatement(
                "DELETE FROM notifications WHERE uuid=? AND grp=? AND expiry=? AND threshold=?")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, group);
            statement.setLong(3, expiry.getEpochSecond());
            statement.setLong(4, threshold.seconds());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw wrap("снятие записи уведомления", e);
        }
    }

    @Override
    public synchronized List<SentRecord> sent(UUID uuid, String group) throws StorageException {
        List<SentRecord> result = new ArrayList<>();
        try (PreparedStatement statement = db.prepareStatement(
                "SELECT expiry, threshold, sent_at FROM notifications WHERE uuid=? AND grp=? ORDER BY expiry, threshold DESC")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, group);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new SentRecord(uuid, group, Instant.ofEpochSecond(rows.getLong(1)),
                            Threshold.ofSeconds(rows.getLong(2)), Instant.ofEpochSecond(rows.getLong(3))));
                }
            }
        } catch (SQLException e) {
            throw wrap("чтение уведомлений", e);
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized void observeActive(UUID uuid, String group, Instant expiry, Instant now) throws StorageException {
        try {
            db.setAutoCommit(false);
            try (PreparedStatement supersede = db.prepareStatement(
                    "UPDATE tracked SET state=? WHERE uuid=? AND grp=? AND expiry<>? AND state=?");
                 PreparedStatement upsert = db.prepareStatement(
                    "INSERT INTO tracked(uuid, grp, expiry, first_seen, last_seen, state) VALUES(?,?,?,?,?,?) "
                    + "ON CONFLICT(uuid, grp, expiry) DO UPDATE SET last_seen=excluded.last_seen, state=excluded.state")) {
                supersede.setString(1, TrackedState.SUPERSEDED.name());
                supersede.setString(2, uuid.toString());
                supersede.setString(3, group);
                supersede.setLong(4, expiry.getEpochSecond());
                supersede.setString(5, TrackedState.ACTIVE.name());
                supersede.executeUpdate();
                upsert.setString(1, uuid.toString());
                upsert.setString(2, group);
                upsert.setLong(3, expiry.getEpochSecond());
                upsert.setLong(4, now.getEpochSecond());
                upsert.setLong(5, now.getEpochSecond());
                upsert.setString(6, TrackedState.ACTIVE.name());
                upsert.executeUpdate();
                db.commit();
            } catch (SQLException e) {
                db.rollback();
                throw e;
            } finally {
                db.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw wrap("запись отслеживаемого срока", e);
        }
    }

    @Override
    public synchronized void supersedeActive(UUID uuid, String group) throws StorageException {
        try (PreparedStatement statement = db.prepareStatement(
                "UPDATE tracked SET state=? WHERE uuid=? AND grp=? AND state=?")) {
            statement.setString(1, TrackedState.SUPERSEDED.name());
            statement.setString(2, uuid.toString());
            statement.setString(3, group);
            statement.setString(4, TrackedState.ACTIVE.name());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw wrap("обновление отслеживаемых сроков", e);
        }
    }

    @Override
    public synchronized List<TrackedSubscription> tracked(UUID uuid, String group) throws StorageException {
        List<TrackedSubscription> result = new ArrayList<>();
        try (PreparedStatement statement = db.prepareStatement(
                "SELECT expiry, first_seen, last_seen, state FROM tracked WHERE uuid=? AND grp=? ORDER BY expiry DESC")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, group);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new TrackedSubscription(uuid, group, Instant.ofEpochSecond(rows.getLong(1)),
                            Instant.ofEpochSecond(rows.getLong(2)), Instant.ofEpochSecond(rows.getLong(3)),
                            TrackedState.valueOf(rows.getString(4))));
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            throw wrap("чтение отслеживаемых сроков", e);
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized void setState(UUID uuid, String group, Instant expiry, TrackedState state) throws StorageException {
        try (PreparedStatement statement = db.prepareStatement(
                "UPDATE tracked SET state=? WHERE uuid=? AND grp=? AND expiry=?")) {
            statement.setString(1, state.name());
            statement.setString(2, uuid.toString());
            statement.setString(3, group);
            statement.setLong(4, expiry.getEpochSecond());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw wrap("обновление состояния срока", e);
        }
    }

    @Override
    public synchronized int prune(Instant olderThan) throws StorageException {
        int removed = 0;
        for (String table : List.of("notifications", "tracked")) {
            try (PreparedStatement statement = db.prepareStatement("DELETE FROM " + table + " WHERE expiry < ?")) {
                statement.setLong(1, olderThan.getEpochSecond());
                removed += statement.executeUpdate();
            } catch (SQLException e) {
                throw wrap("очистка истории", e);
            }
        }
        return removed;
    }

    @Override
    public synchronized void close() throws StorageException {
        try {
            db.close();
        } catch (SQLException e) {
            throw wrap("закрытие", e);
        }
    }

    private static StorageException wrap(String action, Exception e) {
        return new StorageException(action + ": " + e.getMessage(), e);
    }
}
