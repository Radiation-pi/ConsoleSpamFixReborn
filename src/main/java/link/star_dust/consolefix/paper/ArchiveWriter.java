package link.star_dust.consolefix.paper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;

final class ArchiveWriter implements AutoCloseable {
    private record Entry(String rule, LogEvent event) {}
    private final ArrayBlockingQueue<Entry> queue;
    private final Path directory;
    private final Settings.Archive settings;
    private final Thread worker;
    private final LongAdder discarded = new LongAdder();
    private volatile boolean accepting = true;
    private volatile String failure;

    ArchiveWriter(Path directory, Settings.Archive settings) throws IOException {
        this.directory = directory;
        this.settings = settings;
        Files.createDirectories(directory);
        // Detect unwritable paths before a reload replaces the working ruleset.
        try (var ignored = Files.newOutputStream(directory.resolve("filtered.log"),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {}
        queue = new ArrayBlockingQueue<>(settings.capacity());
        worker = Thread.ofPlatform().daemon().name("ConsoleFix-archive").unstarted(this::writeLoop);
        worker.start();
    }

    synchronized void offer(String rule, LogEvent event) {
        if (!accepting || !queue.offer(new Entry(rule, event.toImmutable()))) discarded.increment();
    }
    long discarded() { return discarded.sum(); }
    String failure() { return failure; }

    private void writeLoop() {
        PatternLayout layout = PatternLayout.newBuilder().setPattern("[%d{yyyy-MM-dd HH:mm:ss.SSS}] [%t/%level] [%logger] %msg%n%ex{full}").build();
        Path file = directory.resolve("filtered.log");
        OutputStream output = null;
        try {
            long size = Files.size(file);
            output = new BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.APPEND));
            while (accepting || !queue.isEmpty()) {
                Entry entry = queue.poll(200, TimeUnit.MILLISECONDS);
                if (entry == null) { output.flush(); continue; }
                byte[] data = ("[rule=" + entry.rule() + "] " + layout.toSerializable(entry.event())).getBytes(StandardCharsets.UTF_8);
                if (size > 0 && size + data.length > settings.maxBytes()) {
                    output.close(); output = null;
                    rotate(file);
                    output = new BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
                    size = 0;
                }
                output.write(data);
                size += data.length;
                if (queue.isEmpty()) output.flush();
            }
        } catch (IOException e) {
            failure = e.toString();
            discarded.increment();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                accepting = false;
                discarded.add(queue.size());
                queue.clear();
            }
            if (output != null) {
                try { output.close(); }
                catch (IOException e) { failure = e.toString(); }
            }
        }
    }

    private void rotate(Path file) throws IOException {
        if (settings.maxFiles() == 1) { Files.deleteIfExists(file); return; }
        Files.deleteIfExists(directory.resolve("filtered.log." + (settings.maxFiles() - 1)));
        for (int n = settings.maxFiles() - 2; n >= 1; n--) {
            Path source = directory.resolve("filtered.log." + n);
            if (Files.exists(source)) Files.move(source, directory.resolve("filtered.log." + (n + 1)), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(file, directory.resolve("filtered.log.1"), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void close() {
        synchronized (this) { accepting = false; }
        try {
            worker.join(3000);
            if (worker.isAlive()) worker.interrupt();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); worker.interrupt(); }
    }
}
