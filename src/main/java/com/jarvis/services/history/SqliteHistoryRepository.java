package com.jarvis.services.history;

import com.jarvis.api.CommandStatus;
import com.jarvis.api.CancellationToken;
import com.jarvis.api.ErrorCode;
import com.jarvis.api.HistoryEntry;
import com.jarvis.api.HistoryRepository;
import com.jarvis.api.ServiceException;
import com.jarvis.api.StructuredError;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite/JDBC history persistence.
 *
 * <p>Per CONTRACTS.md the constructor validates configuration and performs
 * no blocking work, so the database is opened and migrated lazily on first
 * use, under a lock. Migrations are versioned SQL resources under
 * {@code /db}, gated on {@code PRAGMA user_version} and applied inside a
 * single transaction each. All reads are bounded ({@code LIMIT ?}) and
 * ordered by {@code completed_at DESC, request_id DESC}; every write is a
 * single transaction with prepared statements; timestamps persist as
 * {@link Instant#toString()} ISO-8601 UTC text.</p>
 *
 * <p>Transactions here are database-only: they never roll back OS actions
 * already taken by other services.</p>
 */
public final class SqliteHistoryRepository implements HistoryRepository {

    static final int SCHEMA_VERSION = 1;
    static final int MAX_LIMIT = 50;

    static final String MIGRATION_RESOURCE = "/db/001_history.sql";

    private final Path databaseFile;
    private final Object lifecycleLock = new Object();

    private Connection connection;
    private boolean closed;

    public SqliteHistoryRepository(Path databaseFile) {
        if (databaseFile == null) {
            throw new IllegalArgumentException("databaseFile must not be null");
        }
        if (databaseFile.toString().isBlank()) {
            throw new IllegalArgumentException("databaseFile must not be blank");
        }
        this.databaseFile = databaseFile.toAbsolutePath();
    }

    Path databaseFile() {
        return databaseFile;
    }

    @Override
    public void save(HistoryEntry entry) throws ServiceException {
        java.util.Objects.requireNonNull(entry, "entry");
        Connection db = connection();
        String sql = "INSERT INTO command_history("
                + "request_id, original_text, status, summary, error_code, error_message, "
                + "submitted_at, started_at, completed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        synchronized (lifecycleLock) {
            try (PreparedStatement statement = db.prepareStatement(sql)) {
                statement.setString(1, entry.requestId().toString());
                statement.setString(2, entry.originalText());
                statement.setString(3, entry.status().name());
                statement.setString(4, entry.summary());
                statement.setString(5, entry.error().map(e -> e.code().name()).orElse(null));
                statement.setString(6, entry.error().map(StructuredError::message).orElse(null));
                statement.setString(7, entry.submittedAt().toString());
                statement.setString(8, entry.startedAt().toString());
                statement.setString(9, entry.completedAt().toString());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw failure("Could not save history entry", e);
            }
        }
    }

    @Override
    public List<HistoryEntry> recent(int limit, CancellationToken cancellation) throws ServiceException {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.INVALID_COMMAND,
                    "History limit must be between 1 and " + MAX_LIMIT,
                    Optional.of("requested limit=" + limit)));
        }
        java.util.Objects.requireNonNull(cancellation, "cancellation");
        Connection db = connection();
        String sql = "SELECT request_id, original_text, status, summary, error_code, error_message, "
                + "submitted_at, started_at, completed_at "
                + "FROM command_history "
                + "ORDER BY completed_at DESC, request_id DESC LIMIT ?";
        synchronized (lifecycleLock) {
            try (PreparedStatement statement = db.prepareStatement(sql)) {
                statement.setInt(1, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<HistoryEntry> entries = new ArrayList<>();
                    while (resultSet.next()) {
                        if (cancellation.isCancellationRequested()) {
                            throw new ServiceException(new StructuredError(
                                    ErrorCode.CANCELLED,
                                    "History read cancelled",
                                    Optional.empty()));
                        }
                        entries.add(readEntry(resultSet));
                    }
                    return entries;
                }
            } catch (SQLException e) {
                throw failure("Could not read history", e);
            }
        }
    }

    @Override
    public void close() throws ServiceException {
        synchronized (lifecycleLock) {
            closed = true;
            if (connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (SQLException e) {
                throw failure("Could not close history database", e);
            } finally {
                connection = null;
            }
        }
    }

    private Connection connection() throws ServiceException {
        synchronized (lifecycleLock) {
            if (closed) {
                throw new ServiceException(new StructuredError(
                        ErrorCode.DATABASE_FAILURE,
                        "History repository is closed",
                        Optional.empty()));
            }
            if (connection != null) {
                return connection;
            }
            try {
                connection = openAndMigrate();
                return connection;
            } catch (IOException | SQLException e) {
                connection = null;
                throw failure("Could not open history database at " + databaseFile, e);
            }
        }
    }

    private Connection openAndMigrate() throws IOException, SQLException {
        Path parent = databaseFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
            if (!Files.isWritable(parent)) {
                throw new IOException("Database directory is not writable: " + parent);
            }
        }
        Connection db = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try {
            applyPragmas(db);
            int version = userVersion(db);
            for (Migration migration : MIGRATIONS) {
                if (migration.version() > version) {
                    applyMigration(db, migration);
                }
            }
        } catch (SQLException | IOException e) {
            try {
                db.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        return db;
    }

    private static void applyPragmas(Connection db) throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
        }
    }

    private static void applyMigration(Connection db, Migration migration) throws SQLException, IOException {
        String sql = readResource(migration.resource());
        db.setAutoCommit(false);
        try (Statement statement = db.createStatement()) {
            for (String part : sql.split(";")) {
                String runnable = part.strip();
                if (!runnable.isEmpty()) {
                    statement.execute(runnable);
                }
            }
            db.commit();
        } catch (SQLException e) {
            try {
                db.rollback();
            } catch (SQLException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    private static int userVersion(Connection db) throws SQLException {
        try (Statement statement = db.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            if (!resultSet.next()) {
                throw new SQLException("PRAGMA user_version returned no row");
            }
            return resultSet.getInt(1);
        }
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream stream = SqliteHistoryRepository.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Migration resource missing: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ServiceException failure(String message, Exception cause) {
        String detail = cause.getMessage();
        return new ServiceException(new StructuredError(
                ErrorCode.DATABASE_FAILURE,
                message,
                detail == null ? Optional.empty() : Optional.of(detail)),
                cause);
    }

    private static HistoryEntry readEntry(ResultSet resultSet) throws SQLException {
        String errorCode = resultSet.getString("error_code");
        String errorMessage = resultSet.getString("error_message");
        Optional<StructuredError> error = errorCode == null
                ? Optional.empty()
                : Optional.of(new StructuredError(
                        ErrorCode.valueOf(errorCode),
                        errorMessage == null ? "Unspecified error" : errorMessage,
                        Optional.empty()));
        return new HistoryEntry(
                UUID.fromString(resultSet.getString("request_id")),
                resultSet.getString("original_text"),
                CommandStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("summary"),
                error,
                Instant.parse(resultSet.getString("submitted_at")),
                Instant.parse(resultSet.getString("started_at")),
                Instant.parse(resultSet.getString("completed_at")));
    }

    private record Migration(int version, String resource) {
    }

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, MIGRATION_RESOURCE));
}
