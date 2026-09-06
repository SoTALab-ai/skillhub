package com.iflytek.skillhub.controller.support;

import com.iflytek.skillhub.config.SkillPublishProperties;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.domain.skill.validation.SkillPackagePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
public class SkillPackageArchiveExtractor {

    private static final Logger log = LoggerFactory.getLogger(SkillPackageArchiveExtractor.class);

    public record ExtractionResult(List<PackageEntry> entries, List<String> warnings) {}

    private final long maxTotalPackageSize;
    private final long maxSingleFileSize;
    private final int maxFileCount;

    public SkillPackageArchiveExtractor(SkillPublishProperties properties) {
        this.maxTotalPackageSize = properties.getMaxPackageSize();
        this.maxSingleFileSize = properties.getMaxSingleFileSize();
        this.maxFileCount = properties.getMaxFileCount();
    }

    public List<PackageEntry> extract(MultipartFile file) throws IOException {
        if (file.getSize() > maxTotalPackageSize) {
            throw new IllegalArgumentException(
                    "Package too large: " + file.getSize() + " bytes (max: "
                            + maxTotalPackageSize + ")"
            );
        }

        Path temporaryArchive = Files.createTempFile("skillhub-upload-", ".zip");
        try {
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporaryArchive, StandardCopyOption.REPLACE_EXISTING);
            }

            List<PackageEntry> entries = new ArrayList<>();
            long totalSize = 0;

            try (ZipFile zipFile = new ZipFile(temporaryArchive.toFile(), StandardCharsets.UTF_8)) {
                Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
                while (zipEntries.hasMoreElements()) {
                    ZipEntry zipEntry = zipEntries.nextElement();
                    if (isDirectoryEntry(zipEntry)) {
                        continue;
                    }

                    if (isOsMetadataEntry(zipEntry.getName())) {
                        continue;
                    }

                    if (entries.size() >= maxFileCount) {
                        throw new IllegalArgumentException(
                                "Too many files: more than " + maxFileCount
                        );
                    }

                    String normalizedPath = SkillPackagePolicy.normalizeEntryPath(zipEntry.getName());
                    byte[] content;
                    try (InputStream entryInput = zipFile.getInputStream(zipEntry)) {
                        content = readEntry(entryInput, normalizedPath);
                    }
                    totalSize += content.length;
                    if (totalSize > maxTotalPackageSize) {
                        throw new IllegalArgumentException(
                                "Package too large: " + totalSize + " bytes (max: "
                                        + maxTotalPackageSize + ")"
                        );
                    }

                    entries.add(new PackageEntry(
                            normalizedPath,
                            content,
                            content.length,
                            determineContentType(normalizedPath)
                    ));
                }
            }

            return stripSingleRootDirectory(entries);
        } finally {
            try {
                Files.deleteIfExists(temporaryArchive);
            } catch (IOException cleanupError) {
                log.warn("Failed to delete temporary upload archive: {}", temporaryArchive, cleanupError);
            }
        }
    }

    public ExtractionResult extractWithWarnings(MultipartFile file) throws IOException {
        List<PackageEntry> entries = extract(file);
        return promoteSingleSkillMdDirectory(entries);
    }

    /**
     * If all file paths share a single root directory prefix (e.g., "my-skill/xxx"),
     * strip that prefix. Otherwise return entries unchanged.
     */
    static List<PackageEntry> stripSingleRootDirectory(List<PackageEntry> entries) {
        if (entries.isEmpty()) return entries;

        Set<String> rootSegments = new HashSet<>();
        for (PackageEntry entry : entries) {
            int slashIndex = entry.path().indexOf('/');
            if (slashIndex < 0) {
                // File at root level, no stripping
                return entries;
            }
            rootSegments.add(entry.path().substring(0, slashIndex));
        }

        if (rootSegments.size() != 1) {
            return entries;
        }

        String prefix = rootSegments.iterator().next() + "/";
        return entries.stream()
                .map(e -> new PackageEntry(
                        e.path().substring(prefix.length()),
                        e.content(),
                        e.size(),
                        e.contentType()))
                .toList();
    }

    static ExtractionResult promoteSingleSkillMdDirectory(List<PackageEntry> entries) {
        boolean hasRootSkillMd = entries.stream()
                .anyMatch(e -> SkillPackagePolicy.SKILL_MD_PATH.equals(e.path()));
        if (hasRootSkillMd) {
            return new ExtractionResult(entries, List.of());
        }

        Set<String> skillMdDirs = new HashSet<>();
        for (PackageEntry entry : entries) {
            int slashIndex = entry.path().indexOf('/');
            if (slashIndex > 0) {
                String relativePath = entry.path().substring(slashIndex + 1);
                if (SkillPackagePolicy.SKILL_MD_PATH.equals(relativePath)) {
                    skillMdDirs.add(entry.path().substring(0, slashIndex));
                }
            }
        }

        if (skillMdDirs.isEmpty()) {
            return new ExtractionResult(entries, List.of());
        }
        if (skillMdDirs.size() > 1) {
            throw new IllegalArgumentException(
                    "Ambiguous package: SKILL.md found in multiple directories: " + skillMdDirs);
        }

        String prefix = skillMdDirs.iterator().next() + "/";
        List<PackageEntry> promoted = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (PackageEntry entry : entries) {
            if (entry.path().startsWith(prefix)) {
                promoted.add(new PackageEntry(
                        entry.path().substring(prefix.length()),
                        entry.content(),
                        entry.size(),
                        entry.contentType()));
            } else {
                warnings.add("Ignored file outside skill directory: " + entry.path());
            }
        }

        return new ExtractionResult(promoted, warnings);
    }

    /**
     * {@link ZipEntry#isDirectory()} only recognizes the ZIP-standard forward slash. Some Windows
     * archive tools emit directory entries whose names end in a backslash instead.
     */
    static boolean isDirectoryEntry(ZipEntry entry) {
        return entry.isDirectory() || entry.getName().endsWith("\\");
    }

    private static boolean isOsMetadataEntry(String name) {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("__MACOSX/") || normalized.equals("__MACOSX")) return true;
        String fileName = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        return fileName.equals(".DS_Store") || fileName.equals(".gitkeep") || fileName.startsWith("._");
    }

    private byte[] readEntry(InputStream input, String path) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long totalRead = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > maxSingleFileSize) {
                throw new IllegalArgumentException(
                        "File too large: " + path + " (" + totalRead + " bytes, max: "
                                + maxSingleFileSize + ")"
                );
            }
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private String determineContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".py")) return "text/x-python";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "application/x-yaml";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".js") || lower.endsWith(".cjs") || lower.endsWith(".mjs")) return "text/javascript";
        if (lower.endsWith(".ts")) return "text/typescript";
        if (lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh")) return "text/x-shellscript";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".toml")) return "application/toml";
        return "application/octet-stream";
    }
}
