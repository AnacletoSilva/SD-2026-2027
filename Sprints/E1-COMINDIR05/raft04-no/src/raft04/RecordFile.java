package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class RecordFile {
    private final Path path;

    public RecordFile(String fileName) {
        this.path = Paths.get(fileName);
    }

    public synchronized void append(
            SensorRecord record
    ) throws IOException {
        Files.writeString(
                path,
                record.toLine() + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    public synchronized SensorRecord last()
            throws IOException {
        List<SensorRecord> records = all();

        return records.isEmpty()
                ? null
                : records.get(records.size() - 1);
    }

    public synchronized long lastSeq()
            throws IOException {
        SensorRecord record = last();

        return record == null
                ? 0
                : record.getSeq();
    }

    public synchronized List<SensorRecord> all()
            throws IOException {
        List<SensorRecord> records = new ArrayList<>();

        if (!Files.exists(path)) {
            return records;
        }

        for (String line : Files.readAllLines(
                path,
                StandardCharsets.UTF_8
        )) {
            if (!line.isBlank()) {
                records.add(
                        SensorRecord.fromLine(line)
                );
            }
        }

        return records;
    }

    public synchronized List<SensorRecord> range(
            long from,
            long to
    ) throws IOException {
        List<SensorRecord> result = new ArrayList<>();

        for (SensorRecord record : all()) {
            if (record.getSeq() >= from
                    && record.getSeq() <= to) {
                result.add(record);
            }
        }

        return result;
    }

    public synchronized void truncateAfter(
            long sequence
    ) throws IOException {
        List<SensorRecord> kept = new ArrayList<>();

        for (SensorRecord record : all()) {
            if (record.getSeq() <= sequence) {
                kept.add(record);
            }
        }

        replaceAtomically(kept);
    }

    public synchronized void replaceAtomically(
            List<SensorRecord> records
    ) throws IOException {
        Path temporary = path.resolveSibling(
                path.getFileName() + ".tmp"
        );

        List<String> lines = new ArrayList<>();

        for (SensorRecord record : records) {
            lines.add(record.toLine());
        }

        Files.write(
                temporary,
                lines,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        try {
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }
}