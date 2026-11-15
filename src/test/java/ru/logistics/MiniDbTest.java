package ru.logistics;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Интеграционные проверки SQL-команд, ограничений и файлового восстановления. */
public class MiniDbTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void insertsSelectsUpdatesAndDeletesRows() throws Exception {
        MiniDb db = newDatabase();
        db.execute("INSERT INTO vehicles VALUES (1, 'A123AA', 'Volvo', 18.5, TRUE)");
        db.execute("INSERT INTO shipments VALUES (10, 1, 'Казань', 'Оборудование', 2.75, 2026-05-20, FALSE)");
        String selected = capture(() -> db.execute("SELECT destination, cargo FROM shipments WHERE weight > 2"));
        assertTrue(selected.contains("Казань"));
        db.execute("UPDATE shipments SET delivered = TRUE WHERE id = 10");
        String updated = capture(() -> db.execute("SELECT delivered FROM shipments WHERE id = 10"));
        assertTrue(updated.contains("true"));
        db.execute("DELETE FROM shipments WHERE delivered = TRUE");
        assertTrue(capture(() -> db.execute("SELECT * FROM shipments")).contains("0 строк"));
    }

    @Test public void rejectsDuplicatePrimaryKeysAndBrokenForeignKeys() throws Exception {
        MiniDb db = newDatabase();
        db.execute("INSERT INTO vehicles VALUES (1, 'A123AA', 'Volvo', 18.5, TRUE)");
        expectFailure(() -> db.execute("INSERT INTO vehicles VALUES (1, 'B123BB', 'MAN', 15.0, TRUE)"));
        expectFailure(() -> db.execute("INSERT INTO shipments VALUES (11, 999, 'Пермь', 'Паллеты', 1.0, 2026-06-01, FALSE)"));
    }

    @Test public void persistsRowsAndIndexesAcrossRestart() throws Exception {
        java.io.File directory = temporaryFolder.newFolder();
        MiniDb first = new MiniDb(directory.toPath());
        first.initialize();
        first.execute("INSERT INTO vehicles VALUES (1, 'A123AA', 'Volvo', 18.5, TRUE)");
        first.execute("INSERT INTO shipments VALUES (10, 1, 'Казань', 'Оборудование', 2.75, 2026-05-20, FALSE)");
        first.execute("CREATE INDEX idx_shipments_destination ON shipments (destination)");

        MiniDb restarted = new MiniDb(directory.toPath());
        restarted.initialize();
        String result = capture(() -> restarted.execute("SELECT id FROM shipments WHERE destination = 'Казань'"));
        assertTrue(result.contains("10"));
        assertTrue(result.contains("использован индекс: destination"));
    }

    @Test public void supportsCreateAndDropTable() throws Exception {
        MiniDb db = newDatabase();
        db.execute("CREATE TABLE depots (id INT PRIMARY KEY, name STRING, active BOOLEAN)");
        db.execute("INSERT INTO depots VALUES (1, 'Центральный', TRUE)");
        db.execute("DROP TABLE depots");
        expectFailure(() -> db.execute("SELECT * FROM depots"));
    }

    private MiniDb newDatabase() throws Exception {
        MiniDb db = new MiniDb(temporaryFolder.newFolder().toPath());
        db.initialize();
        return db;
    }

    private static String capture(Runnable action) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes, true, "UTF-8"));
            action.run();
        } finally {
            System.setOut(original);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void expectFailure(Runnable action) {
        try { action.run(); fail("Ожидалось исключение IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }
}
