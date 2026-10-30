package dev.silverx.llmcraft.game;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Reads the tail of logs/latest.log without loading the whole file. */
public final class LogTail {
    private LogTail() {}

    public static Path latestLog() {
        return FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
    }

    public static List<String> tail(int maxLines, String filterRegex) throws IOException {
        Path p = latestLog();
        if (!Files.exists(p)) return List.of("(no logs/latest.log found)");
        Pattern filter = filterRegex == null || filterRegex.isBlank() ? null : Pattern.compile(filterRegex, Pattern.CASE_INSENSITIVE);
        List<String> out = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
            long len = raf.length();
            // Read at most the last 2 MiB; filtered reads need more lines than unfiltered ones.
            long start = Math.max(0, len - 2L * 1024 * 1024);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String[] lines = new String(buf, StandardCharsets.UTF_8).split("\r?\n");
            for (int i = lines.length - 1; i >= 0 && out.size() < maxLines; i--) {
                if (i == 0 && start > 0) break; // partial first line
                String line = lines[i];
                if (filter == null || filter.matcher(line).find()) out.addFirst(line);
            }
        }
        return out;
    }
}
