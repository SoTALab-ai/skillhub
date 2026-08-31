package com.iflytek.skillhub.domain.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.event.SkillPublishedEvent;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.review.ReviewTaskStatus;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SecurityScanService {

    private static final Logger log = LoggerFactory.getLogger(SecurityScanService.class);
    private static final String TEMP_DIR = "/tmp/skillhub-scans";
    private static final Path TEMP_BASE_DIR = Paths.get(TEMP_DIR).toAbsolutePath().normalize();

    private final SecurityAuditRepository auditRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final ScanTaskOutboxRepository scanTaskOutboxRepository;
    private final ScanTaskProducer scanTaskProducer;
    private final ObjectMapper objectMapper;
    private final String scanMode;
    private final boolean enabled;
    private final SkillRepository skillRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final boolean autoApproveAfterScan;

    @Autowired
    public SecurityScanService(SecurityAuditRepository auditRepository,
                               SkillVersionRepository skillVersionRepository,
                               ScanTaskProducer scanTaskProducer,
                               ObjectMapper objectMapper,
                               @Value("${skillhub.security.scanner.mode:local}") String scanMode,
                               @Value("${skillhub.security.scanner.enabled:false}") boolean enabled,
                               SkillRepository skillRepository,
                               ReviewTaskRepository reviewTaskRepository,
                               ApplicationEventPublisher eventPublisher,
                               @Value("${skillhub.publish.auto-approve-after-scan:false}") boolean autoApproveAfterScan,
                               ScanTaskOutboxRepository scanTaskOutboxRepository) {
        this.auditRepository = auditRepository;
        this.skillVersionRepository = skillVersionRepository;
        this.scanTaskProducer = scanTaskProducer;
        this.objectMapper = objectMapper;
        this.scanMode = scanMode;
        this.enabled = enabled;
        this.skillRepository = skillRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.eventPublisher = eventPublisher;
        this.autoApproveAfterScan = autoApproveAfterScan;
        this.scanTaskOutboxRepository = scanTaskOutboxRepository;
    }

    public SecurityScanService(SecurityAuditRepository auditRepository,
                               SkillVersionRepository skillVersionRepository,
                               ScanTaskProducer scanTaskProducer,
                               ObjectMapper objectMapper,
                               String scanMode,
                               boolean enabled) {
        this(auditRepository, skillVersionRepository, scanTaskProducer, objectMapper, scanMode, enabled,
                null, null, null, false, null);
    }

    public SecurityScanService(SecurityAuditRepository auditRepository,
                               SkillVersionRepository skillVersionRepository,
                               ScanTaskProducer scanTaskProducer,
                               ObjectMapper objectMapper,
                               String scanMode,
                               boolean enabled,
                               ScanTaskOutboxRepository scanTaskOutboxRepository) {
        this(auditRepository, skillVersionRepository, scanTaskProducer, objectMapper, scanMode, enabled,
                null, null, null, false, scanTaskOutboxRepository);
    }

    SecurityScanService(SecurityAuditRepository auditRepository,
                        SkillVersionRepository skillVersionRepository,
                        ScanTaskProducer scanTaskProducer,
                        ObjectMapper objectMapper,
                        String scanMode,
                        boolean enabled,
                        SkillRepository skillRepository,
                        ReviewTaskRepository reviewTaskRepository,
                        ApplicationEventPublisher eventPublisher,
                        boolean autoApproveAfterScan) {
        this(auditRepository, skillVersionRepository, scanTaskProducer, objectMapper, scanMode, enabled,
                skillRepository, reviewTaskRepository, eventPublisher, autoApproveAfterScan, null);
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Transactional
    public void triggerScan(Long versionId, List<PackageEntry> entries, String publisherId) {
        if (!enabled) {
            log.debug("Security scanner disabled, skipping trigger for versionId={}", versionId);
            return;
        }

        SkillVersion version = skillVersionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalStateException("SkillVersion not found: " + versionId));

        String packagePath = null;
        String bundleKey = null;
        if ("upload".equalsIgnoreCase(scanMode)) {
            validateUploadEntries(entries);
            bundleKey = buildBundleStorageKey(version.getSkillId(), versionId);
        } else {
            packagePath = saveTempDirectory(versionId, entries).toString();
        }
        // Always create a new audit record — supports multiple rounds per version
        final ScanTask scanTask = new ScanTask(
                UUID.randomUUID().toString(),
                versionId,
                packagePath,
                bundleKey,
                publisherId,
                System.currentTimeMillis(),
                Map.of("scannerType", ScannerType.SKILL_SCANNER.getValue())
        );
        auditRepository.save(new SecurityAudit(versionId, ScannerType.SKILL_SCANNER, scanTask.taskId()));
        if (scanTaskOutboxRepository != null) {
            scanTaskOutboxRepository.save(new ScanTaskOutbox(scanTask));
        } else {
            TransactionCommitCallbacks.afterCommitOrNow(() -> scanTaskProducer.publishScanTask(scanTask));
        }
        // Only transition to SCANNING if the version is not already published (auto-publish flow)
        if (version.getStatus() != SkillVersionStatus.PUBLISHED) {
            version.setStatus(SkillVersionStatus.SCANNING);
            skillVersionRepository.save(version);
        }
    }

    public boolean isTaskAlreadyProcessed(String taskId) {
        return taskId != null && auditRepository != null
                && auditRepository.existsByTaskIdAndScannedAtIsNotNull(taskId);
    }

    @Transactional
    public void processScanResult(Long versionId, ScannerType scannerType, SecurityScanResponse response) {
        SecurityAudit audit = auditRepository.findLatestActiveByVersionIdAndScannerType(versionId, scannerType)
                .orElseThrow(() -> new IllegalStateException(
                        "SecurityAudit not found for versionId=" + versionId + ", scannerType=" + scannerType));
        SkillVersion version = skillVersionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalStateException("SkillVersion not found: " + versionId));

        audit.setScanId(response.scanId());
        audit.setVerdict(response.verdict());
        audit.setIsSafe(response.verdict() == SecurityVerdict.SAFE);
        audit.setMaxSeverity(response.maxSeverity());
        audit.setFindingsCount(response.findingsCount());
        audit.setFindings(serializeFindings(response.findings()));
        audit.setScanDurationSeconds(response.scanDurationSeconds());
        audit.setScannedAt(Instant.now(Clock.systemUTC()));
        auditRepository.save(audit);

        SkillPublishedEvent publishedEvent = null;

        // Only transition from SCANNING — leave PUBLISHED/REJECTED/YANKED untouched.
        if (version.getStatus() == SkillVersionStatus.SCANNING) {
            if (version.getRequestedVisibility() == SkillVisibility.PRIVATE) {
                version.setStatus(SkillVersionStatus.UPLOADED);
            } else if (autoApproveAfterScan) {
                removePendingReviewTask(version);
                if (response.verdict() == SecurityVerdict.SAFE) {
                    version.setStatus(SkillVersionStatus.PUBLISHED);
                    version.setPublishedAt(Instant.now(Clock.systemUTC()));

                    Skill skill = skillRepository.findById(version.getSkillId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Skill not found for versionId=" + versionId));
                    skill.setLatestVersionId(version.getId());
                    skill.setVisibility(version.getRequestedVisibility());
                    skill.setUpdatedBy(version.getCreatedBy());
                    skillRepository.save(skill);
                    publishedEvent = new SkillPublishedEvent(
                            skill.getId(), version.getId(), version.getCreatedBy());
                    log.info("Security scan auto-approved skill versionId={}", versionId);
                } else {
                    version.setStatus(SkillVersionStatus.REJECTED);
                    log.warn("Security scan auto-rejected skill versionId={} verdict={}",
                            versionId, response.verdict());
                }
            } else {
                version.setStatus(SkillVersionStatus.PENDING_REVIEW);
            }
        }
        skillVersionRepository.save(version);
        if (publishedEvent != null) {
            eventPublisher.publishEvent(publishedEvent);
        }
    }

    private void removePendingReviewTask(SkillVersion version) {
        reviewTaskRepository.findBySkillVersionIdAndStatus(version.getId(), ReviewTaskStatus.PENDING)
                .ifPresent(reviewTaskRepository::delete);
    }

    private Path saveTempDirectory(Long versionId, List<PackageEntry> entries) {
        try {
            Path skillDir = TEMP_BASE_DIR.resolve(String.valueOf(versionId)).normalize();
            Files.createDirectories(skillDir);
            for (PackageEntry entry : entries) {
                Path filePath = resolveSafeChild(skillDir, entry.path());
                Path parent = filePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(filePath, entry.content());
            }
            return skillDir;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save temp directory for versionId: " + versionId, e);
        }
    }

    private void validateUploadEntries(List<PackageEntry> entries) {
        for (PackageEntry entry : entries) {
            safeZipEntryName(entry.path());
        }
    }

    private String buildBundleStorageKey(Long skillId, Long versionId) {
        return String.format("packages/%d/%d/bundle.zip", skillId, versionId);
    }

    private String serializeFindings(List<SecurityFinding> findings) {
        try {
            return objectMapper.writeValueAsString(findings);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize findings for security audit", e);
            return "[]";
        }
    }

    private Path resolveSafeChild(Path baseDir, String entryPath) {
        Path resolved = baseDir.resolve(entryPath).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalStateException("Unsafe scan path: " + entryPath);
        }
        return resolved;
    }

    private String safeZipEntryName(String entryPath) {
        Path normalized = Paths.get(entryPath).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalStateException("Unsafe scan path: " + entryPath);
        }
        String safePath = normalized.toString().replace('\\', '/');
        if (safePath.isBlank() || safePath.startsWith("../")) {
            throw new IllegalStateException("Unsafe scan path: " + entryPath);
        }
        return safePath;
    }

    /**
     * Soft delete all audit records for a given skill version.
     * Called before physically deleting a skill version to preserve audit history.
     */
    @Transactional
    public void softDeleteByVersionId(Long versionId) {
        if (scanTaskOutboxRepository != null) {
            scanTaskOutboxRepository.deleteByVersionId(versionId);
        }
        List<SecurityAudit> audits = auditRepository.findAllActiveBySkillVersionId(versionId);
        if (audits.isEmpty()) {
            log.debug("No active security audits to soft-delete for versionId={}", versionId);
            return;
        }
        audits.forEach(SecurityAudit::markAsDeleted);
        auditRepository.saveAll(audits);
        log.info("Soft deleted {} security audit(s) for versionId={}", audits.size(), versionId);
    }

    /**
     * Physically delete all audit records for a given skill version.
     * Called during hard delete when the entire skill is being permanently removed.
     */
    @Transactional
    public void hardDeleteByVersionId(Long versionId) {
        auditRepository.deleteBySkillVersionId(versionId);
        if (scanTaskOutboxRepository != null) {
            scanTaskOutboxRepository.deleteByVersionId(versionId);
        }
    }
}
