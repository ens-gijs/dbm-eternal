package io.github.ensgijs.dbm.util.io;

import io.github.ensgijs.dbm.util.BubbleUpException;
import io.github.ensgijs.dbm.util.function.ThrowingConsumer;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.FileSystem;
import java.util.Collections;
import java.util.stream.Stream;

/// Utility for walking jar resources agnostic of loading from jar or disk (can be used in prod and test).
public final class ResourceScanner {

    private ResourceScanner() {}

    /**
     * Represents a discovered resource on the classpath.
     * <p>
     * This record encapsulates the location of a resource and the mechanism
     * to access its content. It is designed to be used with {@link ResourceScanner}.
     * </p>
     *
     * @param path        The normalized, relative path of the resource
     * (e.g., "db/migrate/core.1.mysql.sql").
     * Uses forward slashes as separators regardless of the OS.
     * @param classLoader The {@link ClassLoader} that discovered this resource,
     * used to open data streams.
     */
    public record ResourceEntry(String path, ClassLoader classLoader) {
        /**
         * Opens an {@link InputStream} for the resource represented by this entry.
         * <p>
         * <b>Note:</b> The caller is responsible for closing the returned stream.
         * Failure to do so may result in resource leaks.
         * </p>
         * <pre>{@code
         * try (InputStream is = entry.asStream()) {
         *   // read content
         * }
         * }</pre>
         *
         * @return A non-null {@link InputStream} for the resource content.
         * @throws FileNotFoundException if the resource cannot be found or accessed
         * via the provided ClassLoader.
         */
        public InputStream asStream() throws FileNotFoundException {
            InputStream is = classLoader.getResourceAsStream(path);
            if (is == null) {
                throw new FileNotFoundException("Resource no longer available: " + path);
            }
            return is;
        }

        public BufferedReader asReader() throws FileNotFoundException {
            return new BufferedReader(new InputStreamReader(asStream()));
        }
    }

    /**
     * Scans the classpath for resources located under the specified root path.
     * <p>
     * This method provides a unified way to walk resources whether they are residing
     * in a standard directory (common in development/IDE) or packaged within a
     * JAR file (common in production).
     * </p>
     * <p>
     * <b>Important:</b> The returned {@link Stream} is backed by system resources
     * (such as a {@link FileSystem}). To prevent resource leaks, you
     * <strong>must</strong> use this method within a {@code try-with-resources}
     * block so that {@link Stream#close()} is called automatically.
     * </p>
     *
     * <h4>Example Usage:</h4>
     * <pre>{@code
     * String path = "db/migrate";
     * try (Stream<ResourceEntry> entries = ResourceScanner.walkResources(MyClass.class, path)) {
     *   entries.filter(e -> e.path().endsWith(".sql"))
     *     .forEach(entry -> {
     *       try (InputStream is = entry.asStream()) {
     *         // Process the migration file content
     *       } catch (IOException e) {
     *         logger.error("Failed to read: " + entry.path());
     *       }
     *     });
     * } catch (IOException e) {
     *   logger.error("Failed to scan resources at " + path);
     * }
     * }</pre>
     *
     * @param clazz The class whos {@link ClassLoader} should be used for resource discovery.
     * @param rootPath    The relative path within the classpath to scan (e.g., "db/migrate/").
     * @return A {@link Stream} of {@link ResourceEntry} objects representing the discovered files.
     * @throws IOException If an I/O error occurs during the scan or if the root path cannot be converted to a URI.
     * @see ResourceEntry
     */
    public static @NotNull Stream<ResourceEntry> walkResources(@NotNull final Class<?> clazz, @NotNull final String rootPath) throws IOException {
        return walkResources(clazz.getClassLoader(), rootPath);
    }

    /**
     * Scans the classpath for resources located under the specified root path.
     * <p>
     * This method provides a unified way to walk resources whether they are residing
     * in a standard directory (common in development/IDE) or packaged within a
     * JAR file (common in production).
     * </p>
     * <p>
     * <b>Important:</b> The returned {@link Stream} is backed by system resources
     * (such as a {@link FileSystem}). To prevent resource leaks, you
     * <strong>must</strong> use this method within a {@code try-with-resources}
     * block so that {@link Stream#close()} is called automatically.
     * </p>
     *
     * <h4>Example Usage:</h4>
     * <pre>{@code
     * String path = "db/migrate";
     * try (Stream<ResourceEntry> entries = ResourceScanner.walkResources(classLoader, path)) {
     *   entries.filter(e -> e.path().endsWith(".sql"))
     *     .forEach(entry -> {
     *       try (InputStream is = entry.asStream()) {
     *         // Process the migration file content
     *       } catch (IOException e) {
     *         logger.error("Failed to read: " + entry.path());
     *       }
     *     });
     * } catch (IOException e) {
     *   logger.error("Failed to scan resources at " + path);
     * }
     * }</pre>
     *
     * @param classLoader The {@link ClassLoader} to use for resource discovery.
     * @param rootPath    The relative path within the classpath to scan (e.g., "db/migrate/").
     * @return A {@link Stream} of {@link ResourceEntry} objects representing the discovered files.
     * @throws IOException If an I/O error occurs during the scan or if the root path cannot be converted to a URI.
     * @see ResourceEntry
     */
    public static @NotNull Stream<ResourceEntry> walkResources(@NotNull final ClassLoader classLoader, @NotNull String rootPath) throws IOException {
        rootPath = rootPath.replaceAll("\\\\", "/");
        if (rootPath.startsWith("/")) rootPath = rootPath.substring(1);
        if (!rootPath.endsWith("/")) rootPath = rootPath + "/";

        URL url = classLoader.getResource(rootPath);
        if (url == null) return Stream.empty();

        try {
            URI uri = url.toURI();

            if ("jar".equals(uri.getScheme())) {
                // Initialize the FileSystem for the JAR
                FileSystem fs = getFileSystem(uri);
                final Path base = fs.getPath("");
                Path pathInJar = fs.getPath(rootPath);

                // Walk the JAR and ensure both the walk-stream and the FS are closed
                return Files.walk(pathInJar)
                        .filter(Files::isRegularFile)
                        .map(p -> new ResourceEntry(base.relativize(p).toString().replace("\\", "/"), classLoader))
                        .onClose(() -> {
                            try { fs.close(); } catch (IOException ignored) {}
                        });
            } else {
                // Standard file system walking
                Path localPath = Paths.get(uri);
                // We find the 'root' of the classpath to make paths relative correctly
                Path rp = Path.of(rootPath);
                if (!localPath.endsWith(rp))
                    throw new IllegalAccessError(rootPath + " does not appear to be the suffix of " + localPath);
                Path base = localPath.resolve("../".repeat(rp.getNameCount())).normalize();

                return Files.walk(localPath)
                        .filter(Files::isRegularFile)
                        .map(p -> new ResourceEntry(base.relativize(p).toString().replace("\\", "/"), classLoader));
            }
        } catch (Exception e) {
            throw new IOException("Failed to initialize resource stream: " + rootPath, e);
        }
    }

    public static void visit(
            @NotNull final ClassLoader classLoader,
            @NotNull final String rootPath,
            @NotNull final ThrowingConsumer<ResourceEntry> visitor
    ) throws IOException {
        try (Stream<ResourceEntry> entryStream = walkResources(classLoader, rootPath)
        ) {
            entryStream.forEach(entry -> {
                try {
                    visitor.accept(entry);
                } catch (Throwable ex) {
                    throw new BubbleUpException(ex); // Propagate to outer catch
                }
            });
        } catch (BubbleUpException ex) {
            if (ex.getCause() instanceof IOException iox)
                throw iox;
            throw new IOException(ex.getCause());
        }
    }

    private static FileSystem getFileSystem(URI uri) throws IOException {
        try {
            return FileSystems.getFileSystem(uri);
        } catch (FileSystemNotFoundException e) {
            return FileSystems.newFileSystem(uri, Collections.emptyMap());
        }
    }
}