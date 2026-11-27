package me.spotifypanel;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/** Optional, short-lived disk cache. Spotify artwork is the only persisted content. */
public final class CacheManager {
    private static final Logger LOG = LoggerFactory.getLogger("spotifypanel");
    private static final long COVER_TTL_MS = 60L * 60L * 1_000L;
    private static final long MAX_COVER_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_COVER_FILES = 1_500;
    private static final long MAX_SINGLE_COVER_BYTES = 1L * 1024L * 1024L;

    private final Config config;
    private final Path root;
    private final Path tracksDir;
    private final Path coversDir;
    private final Path lyricsDir;
    private final Path metadataDir;
    private volatile boolean enabled;

    public CacheManager(Config config) {
        this.config = config;
        Path configDir = FabricLoader.getInstance().getConfigDir();
        this.root = configDir.resolve("spotify-panel-cache");
        this.tracksDir = root.resolve("tracks");
        this.coversDir = root.resolve("covers");
        this.lyricsDir = root.resolve("lyrics");
        this.metadataDir = root.resolve("metadata");
        this.enabled = config.ssdCacheEnabled;
    }

    /** Called at startup. No directories are created when the setting is off. */
    public void initialize() {
        if (!enabled) return;
        try {
            createDirectories();
            cleanupExpiredAndOverflow();
        } catch (IOException e) {
            LOG.warn("Не удалось подготовить SSD-кэш Spotify Panel.", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Periodic short-TTL cleanup. Does not touch the filesystem while disabled. */
    public void maintain() {
        if (!enabled) return;
        try {
            cleanupExpiredAndOverflow();
        } catch (IOException e) {
            LOG.debug("Не удалось выполнить очистку временного SSD-кэша.", e);
        }
    }

    public synchronized boolean enable() {
        if (enabled) return true;
        try {
            createDirectories();
            enabled = true;
            config.ssdCacheEnabled = true;
            config.save();
            cleanupExpiredAndOverflow();
            return true;
        } catch (IOException e) {
            enabled = false;
            config.ssdCacheEnabled = false;
            config.save();
            LOG.error("Не удалось включить SSD-кэш Spotify Panel.", e);
            return false;
        }
    }

    public synchronized void disable() {
        enabled = false;
        config.ssdCacheEnabled = false;
        config.save();
    }

    /** Read an unexpired resized cover. Disk I/O is only called by the cover worker. */
    public synchronized byte[] loadCover(String sourceUrl) {
        if (!enabled || sourceUrl == null || sourceUrl.isBlank()) return null;
        Path file = coverPath(sourceUrl);
        try {
            if (!Files.isRegularFile(file)) return null;
            long writtenAt = Files.getLastModifiedTime(file).toMillis();
            long age = System.currentTimeMillis() - writtenAt;
            if (age < 0 || age > COVER_TTL_MS || Files.size(file) > MAX_SINGLE_COVER_BYTES) {
                Files.deleteIfExists(file);
                return null;
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            LOG.debug("Не удалось прочитать временную обложку из SSD-кэша.", e);
            return null;
        }
    }

    /** Store only the already downsampled 256x256 image, never the original CDN file. */
    public synchronized void storeCover(String sourceUrl, byte[] pngBytes) {
        if (!enabled || sourceUrl == null || sourceUrl.isBlank() || pngBytes == null
                || pngBytes.length == 0 || pngBytes.length > MAX_SINGLE_COVER_BYTES) return;
        Path target = coverPath(sourceUrl);
        Path temporary = null;
        try {
            if (!enabled) return;
            Files.createDirectories(coversDir);
            temporary = Files.createTempFile(coversDir, "cover-", ".tmp");
            Files.write(temporary, pngBytes);
            if (!enabled) return;
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveUnavailable) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            cleanupExpiredAndOverflow();
        } catch (IOException e) {
            LOG.debug("Не удалось записать временную обложку в SSD-кэш.", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            }
        }
    }

    public synchronized void removeCover(String sourceUrl) {
        if (!enabled || sourceUrl == null || sourceUrl.isBlank()) return;
        try {
            Files.deleteIfExists(coverPath(sourceUrl));
        } catch (IOException e) {
            LOG.debug("Не удалось удалить повреждённую обложку из SSD-кэша.", e);
        }
    }

    /** Explicit user action; allowed even when SSD caching is switched off. */
    public synchronized boolean clear() {
        try {
            deleteTree(root);
            if (enabled) createDirectories();
            return true;
        } catch (IOException e) {
            LOG.warn("Не удалось полностью очистить SSD-кэш Spotify Panel.", e);
            return false;
        }
    }

    public String statusText() {
        if (!enabled) {
            return "SSD-кэш: ВЫКЛ. Данные на диске не читаются и не записываются. Путь: " + root;
        }
        long[] stats = coverStats();
        return "SSD-кэш: ВКЛ. Временных обложек: " + stats[0] + ", размер: "
                + String.format(java.util.Locale.ROOT, "%.1f МБ", stats[1] / (1024.0 * 1024.0))
                + ". Автоудаление через 1 час. Путь: " + root;
    }

    private void createDirectories() throws IOException {
        Files.createDirectories(tracksDir);
        Files.createDirectories(coversDir);
        Files.createDirectories(lyricsDir);
        Files.createDirectories(metadataDir);
    }

    private Path coverPath(String sourceUrl) {
        return coversDir.resolve(sha256(sourceUrl) + ".png");
    }

    private synchronized void cleanupExpiredAndOverflow() throws IOException {
        if (!enabled || !Files.isDirectory(coversDir)) return;
        long now = System.currentTimeMillis();
        List<CoverFile> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(coversDir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    long modified = Files.getLastModifiedTime(path).toMillis();
                    long size = Files.size(path);
                    String filename = path.getFileName().toString();
                    if (!filename.endsWith(".png") || size <= 0L || size > MAX_SINGLE_COVER_BYTES
                            || modified > now || now - modified > COVER_TTL_MS) {
                        Files.deleteIfExists(path);
                    } else {
                        files.add(new CoverFile(path, modified, size));
                    }
                } catch (IOException e) {
                    LOG.debug("Не удалось проверить файл SSD-кэша {}", path, e);
                }
            });
        }
        files.sort(Comparator.comparingLong(CoverFile::writtenAt));
        long totalBytes = files.stream().mapToLong(CoverFile::size).sum();
        int count = files.size();
        for (CoverFile file : files) {
            if (count <= MAX_COVER_FILES && totalBytes <= MAX_COVER_BYTES) break;
            try {
                if (Files.deleteIfExists(file.path())) {
                    count--;
                    totalBytes -= file.size();
                }
            } catch (IOException e) {
                LOG.debug("Не удалось удалить старую обложку {}", file.path(), e);
            }
        }
    }

    private long[] coverStats() {
        if (!enabled || !Files.isDirectory(coversDir)) return new long[]{0L, 0L};
        long count = 0L;
        long bytes = 0L;
        try (Stream<Path> stream = Files.list(coversDir)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                count++;
                bytes += Files.size(path);
            }
        } catch (IOException e) {
            LOG.debug("Не удалось получить размер SSD-кэша.", e);
        }
        return new long[]{count, bytes};
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record CoverFile(Path path, long writtenAt, long size) {}
}
