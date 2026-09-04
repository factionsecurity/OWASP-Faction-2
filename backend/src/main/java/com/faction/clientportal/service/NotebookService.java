package com.faction.clientportal.service;

import com.faction.clientportal.dto.*;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.NotebookNodeRepository;
import com.faction.clientportal.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotebookService {

    /** Maximum allowed depth (0-indexed), giving 6 levels total: 0..5 */
    private static final int MAX_DEPTH = 5;

    private final NotebookNodeRepository notebookNodeRepository;
    private final InlineImageService inlineImageService;
    private final com.faction.clientportal.repository.AssessmentRepository assessmentRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final ApplicationRepository applicationRepository;
    private final MentionQueueService mentionQueueService;

    // -------------------------------------------------------------------------
    // Assessment integration
    // -------------------------------------------------------------------------

    /**
     * Create a root notebook node automatically when an assessment is created.
     *
     * @param applicationId  application the assessment belongs to
     * @param assessmentId   the new assessment's id
     * @param assessmentName human-readable assessment name
     * @param startDate      assessment start date (used in the title)
     * @param userId         user who created the assessment
     * @return the persisted node
     */
    public NotebookNode createRootNodeForAssessment(
            String applicationId, String assessmentId,
            String assessmentName, LocalDateTime startDate, String userId) {

        String formattedDate = startDate != null
                ? startDate.format(DateTimeFormatter.ofPattern("MMM dd, yyyy"))
                : "No Date";
        String title = assessmentName + " - " + formattedDate;

        String userName = resolveUserName(userId);

        // Use current count as orderIndex so roots are appended in creation order
        long existingRoots = notebookNodeRepository
                .findByApplicationIdAndParentIdIsNullAndDeletedAtIsNullOrderByOrderIndexAsc(applicationId)
                .size();

        NotebookNode node = NotebookNode.builder()
                .applicationId(applicationId)
                .assessmentId(assessmentId)
                .parentId(null)
                .title(title)
                .content("")
                .contentText("")
                .orderIndex((int) existingRoots)
                .depth(0)
                .attachments(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .createdById(userId)
                .createdByName(userName)
                .lastModifiedAt(LocalDateTime.now())
                .modifiedBy(new ArrayList<>())
                .build();

        NotebookNode saved = notebookNodeRepository.save(node);
        log.info("Created root notebook node {} for assessment {} / application {}", saved.getId(), assessmentId, applicationId);
        return saved;
    }

    // -------------------------------------------------------------------------
    // Tree retrieval
    // -------------------------------------------------------------------------

    /**
     * Return the full notebook tree for an application.
     * Assessment-owned roots that have no non-deleted descendants are excluded.
     */
    public List<NotebookNodeDto> getTreeForApplication(String applicationId) {
        List<NotebookNode> roots = notebookNodeRepository
                .findByApplicationIdAndParentIdIsNullAndDeletedAtIsNullOrderByOrderIndexAsc(applicationId);

        // The most recently added root (highest orderIndex) is the current assessment root
        // and is always shown even if empty. Older assessment roots are hidden when empty.
        int maxOrderIndex = roots.stream().mapToInt(NotebookNode::getOrderIndex).max().orElse(-1);

        List<NotebookNodeDto> result = new ArrayList<>();
        for (NotebookNode root : roots) {
            boolean isMostRecent = root.getOrderIndex() == maxOrderIndex;
            // Always show: manually-created roots (no assessmentId) and the most-recent assessment root.
            // Hide older assessment-owned roots that have no descendants.
            if (!isMostRecent && root.getAssessmentId() != null && !hasAnyDescendants(root.getId())) {
                continue;
            }
            NotebookNodeDto dto = buildSubtree(root);
            result.add(dto);
        }
        return result;
    }

    /**
     * Builds the deep link for a mention inside a note.
     *
     * <p>The notebook is only reachable through an assessment, so the link has to name one.
     * Only the auto-created assessment root carries {@code assessmentId} — {@link #create}
     * never sets it on anything else — so every sub-note and every manually-created note
     * had none, and produced a {@code /notebook?node=...} link to a route that does not
     * exist. Walking up to the root recovers it.
     *
     * @return an app-relative link, or null when there is no assessment to open the
     *         notebook in, in which case the notification is left without a link rather
     *         than pointing at a dead URL.
     */
    private String notebookContextLink(NotebookNode node) {
        String assessmentId = resolveAssessmentId(node);
        if (assessmentId == null) {
            // A note under an application with no assessment at all. Rare, and there is
            // nowhere to render the notebook, so no link is better than a broken one.
            log.debug("No assessment found for notebook node {} — mention link omitted", node.getId());
            return null;
        }
        return "/assessments/" + assessmentId + "?section=notebook&node=" + node.getId();
    }

    /** The node's own assessment, else its root ancestor's, else any for the application. */
    private String resolveAssessmentId(NotebookNode node) {
        NotebookNode current = node;
        // MAX_DEPTH bounds the tree, so this cannot loop indefinitely; the guard is against
        // a corrupt parent chain rather than legitimate depth.
        for (int hops = 0; hops <= MAX_DEPTH + 1 && current != null; hops++) {
            if (current.getAssessmentId() != null) return current.getAssessmentId();
            if (current.getParentId() == null) break;
            current = notebookNodeRepository.findByIdAndDeletedAtIsNull(current.getParentId()).orElse(null);
        }

        // Manually-created roots have no assessment of their own, but the tree is loaded
        // per application, so any of that application's assessments can display them.
        return assessmentRepository.findByApplicationIdAndDeletedAtIsNull(node.getApplicationId())
                .stream()
                .max(Comparator.comparing(a -> a.getCreatedAt() == null
                        ? LocalDateTime.MIN : a.getCreatedAt()))
                .map(a -> a.getId())
                .orElse(null);
    }

    private NotebookNodeDto buildSubtree(NotebookNode node) {
        NotebookNodeDto dto = NotebookNodeDto.fromEntity(node);
        List<NotebookNode> children = notebookNodeRepository
                .findByParentIdAndDeletedAtIsNullOrderByOrderIndexAsc(node.getId());

        boolean hasChildren = !children.isEmpty();
        dto.setHasChildren(hasChildren);

        List<NotebookNodeDto> childDtos = children.stream()
                .map(this::buildSubtree)
                .collect(Collectors.toList());
        dto.setChildren(childDtos);
        return dto;
    }

    /**
     * Returns true if the given node has at least one non-deleted descendant (direct or indirect).
     */
    boolean hasAnyDescendants(String nodeId) {
        List<NotebookNode> directChildren = notebookNodeRepository
                .findByParentIdAndDeletedAtIsNullOrderByOrderIndexAsc(nodeId);
        if (!directChildren.isEmpty()) {
            return true;
        }
        // BFS/DFS would be more efficient at scale, but for typical tree sizes this is fine
        return false;
    }

    // -------------------------------------------------------------------------
    // Single node
    // -------------------------------------------------------------------------

    public NotebookNodeDto getNode(String nodeId) {
        NotebookNode node = notebookNodeRepository.findByIdAndDeletedAtIsNull(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Notebook node not found: " + nodeId));
        NotebookNodeDto dto = NotebookNodeDto.fromEntity(node);
        long childCount = notebookNodeRepository.countByParentIdAndDeletedAtIsNull(nodeId);
        dto.setHasChildren(childCount > 0);
        return dto;
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    public NotebookNodeDto createNode(String applicationId, CreateNotebookNodeRequest request, String userId) {
        // Verify application exists
        applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found: " + applicationId));

        int depth;
        if (request.getParentId() == null) {
            depth = 0;
        } else {
            NotebookNode parent = notebookNodeRepository.findByIdAndDeletedAtIsNull(request.getParentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Parent node not found: " + request.getParentId()));
            if (parent.getDepth() >= MAX_DEPTH) {
                throw new IllegalArgumentException(
                        "Maximum notebook depth of " + (MAX_DEPTH + 1) + " levels reached");
            }
            depth = parent.getDepth() + 1;
        }

        // Determine orderIndex
        int orderIndex = request.getOrderIndex();
        if (orderIndex <= 0) {
            long siblingCount = notebookNodeRepository.countByParentIdAndDeletedAtIsNull(
                    request.getParentId() != null ? request.getParentId() : "");
            if (request.getParentId() == null) {
                siblingCount = notebookNodeRepository
                        .findByApplicationIdAndParentIdIsNullAndDeletedAtIsNullOrderByOrderIndexAsc(applicationId)
                        .size();
            }
            orderIndex = (int) siblingCount;
        }

        String content = request.getContent() != null ? request.getContent() : "";
        String contentText = stripHtml(content);
        String userName = resolveUserName(userId);

        NotebookNode node = NotebookNode.builder()
                .applicationId(applicationId)
                .parentId(request.getParentId())
                .title(request.getTitle())
                .content(content)
                .contentText(contentText)
                .orderIndex(orderIndex)
                .depth(depth)
                .attachments(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .createdById(userId)
                .createdByName(userName)
                .lastModifiedAt(LocalDateTime.now())
                .modifiedBy(new ArrayList<>())
                .build();

        NotebookNode saved = notebookNodeRepository.save(node);
        indexInlineImages(saved);
        log.info("Created notebook node {} under application {}", saved.getId(), applicationId);

        if (!content.isBlank()) {
            String contextLink = notebookContextLink(saved);
            mentionQueueService.queueMentions(content, contextLink, userId,
                    MentionTarget.notebook(saved.getId(), saved.getTitle()));
        }

        NotebookNodeDto dto = NotebookNodeDto.fromEntity(saved);
        dto.setHasChildren(false);
        return dto;
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    public NotebookNodeDto updateNode(String nodeId, UpdateNotebookNodeRequest request, String userId) {
        NotebookNode node = notebookNodeRepository.findByIdAndDeletedAtIsNull(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Notebook node not found: " + nodeId));

        if (request.getTitle() != null) {
            node.setTitle(request.getTitle());
        }
        if (request.getContent() != null) {
            node.setContent(request.getContent());
            node.setContentText(stripHtml(request.getContent()));
        }
        if (request.getOrderIndex() != null) {
            node.setOrderIndex(request.getOrderIndex());
        }

        String userName = resolveUserName(userId);
        ModificationRecord record = ModificationRecord.builder()
                .userId(userId)
                .userName(userName)
                .modifiedAt(LocalDateTime.now())
                .build();
        if (node.getModifiedBy() == null) {
            node.setModifiedBy(new ArrayList<>());
        }
        node.getModifiedBy().add(record);
        node.setLastModifiedAt(LocalDateTime.now());

        NotebookNode saved = notebookNodeRepository.save(node);
        indexInlineImages(saved);
        log.info("Updated notebook node {} by user {}", nodeId, userId);

        if (request.getContent() != null && !request.getContent().isBlank()) {
            String contextLink = notebookContextLink(node);
            mentionQueueService.queueMentions(request.getContent(), contextLink, userId,
                    MentionTarget.notebook(nodeId, saved.getTitle()));
        }

        NotebookNodeDto dto = NotebookNodeDto.fromEntity(saved);
        long childCount = notebookNodeRepository.countByParentIdAndDeletedAtIsNull(nodeId);
        dto.setHasChildren(childCount > 0);
        return dto;
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    public void deleteNode(String nodeId, String userId) {
        NotebookNode node = notebookNodeRepository.findByIdAndDeletedAtIsNull(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Notebook node not found: " + nodeId));

        softDeleteRecursive(node);
        log.info("Soft-deleted notebook node {} (and descendants) by user {}", nodeId, userId);
    }

    private void softDeleteRecursive(NotebookNode node) {
        node.setDeletedAt(LocalDateTime.now());
        notebookNodeRepository.save(node);
        // A deleted note no longer holds its screenshots open; they age out with the next GC.
        inlineImageService.updateRefsForSharedField(noteImageFieldKey(node.getId()), "");

        List<NotebookNode> children = notebookNodeRepository
                .findByParentIdAndDeletedAtIsNullOrderByOrderIndexAsc(node.getId());
        for (NotebookNode child : children) {
            softDeleteRecursive(child);
        }
    }

    // -------------------------------------------------------------------------
    // Move
    // -------------------------------------------------------------------------

    public NotebookNodeDto moveNode(String nodeId, MoveNotebookNodeRequest request, String userId) {
        NotebookNode node = notebookNodeRepository.findByIdAndDeletedAtIsNull(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Notebook node not found: " + nodeId));

        int newDepth;
        if (request.getNewParentId() == null) {
            newDepth = 0;
        } else {
            NotebookNode newParent = notebookNodeRepository.findByIdAndDeletedAtIsNull(request.getNewParentId())
                    .orElseThrow(() -> new ResourceNotFoundException("New parent node not found: " + request.getNewParentId()));
            if (newParent.getDepth() >= MAX_DEPTH) {
                throw new IllegalArgumentException(
                        "Maximum notebook depth of " + (MAX_DEPTH + 1) + " levels reached");
            }
            newDepth = newParent.getDepth() + 1;
        }

        node.setParentId(request.getNewParentId());
        node.setOrderIndex(request.getNewOrderIndex());
        node.setDepth(newDepth);

        String userName = resolveUserName(userId);
        ModificationRecord record = ModificationRecord.builder()
                .userId(userId)
                .userName(userName)
                .modifiedAt(LocalDateTime.now())
                .build();
        if (node.getModifiedBy() == null) {
            node.setModifiedBy(new ArrayList<>());
        }
        node.getModifiedBy().add(record);
        node.setLastModifiedAt(LocalDateTime.now());

        NotebookNode saved = notebookNodeRepository.save(node);
        log.info("Moved notebook node {} to parent {} by user {}", nodeId, request.getNewParentId(), userId);

        NotebookNodeDto dto = NotebookNodeDto.fromEntity(saved);
        long childCount = notebookNodeRepository.countByParentIdAndDeletedAtIsNull(nodeId);
        dto.setHasChildren(childCount > 0);
        return dto;
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    public List<NotebookSearchResultDto> searchNodes(
            String applicationId, String q, String createdById,
            LocalDateTime from, LocalDateTime to) {

        List<NotebookNode> allNodes = notebookNodeRepository.findByApplicationIdAndDeletedAtIsNull(applicationId);

        // Build id -> node map for breadcrumb traversal
        Map<String, NotebookNode> nodeMap = allNodes.stream()
                .collect(Collectors.toMap(NotebookNode::getId, n -> n));

        List<NotebookNode> filtered = allNodes.stream()
                .filter(node -> {
                    if (q != null && !q.isBlank()) {
                        String lower = q.toLowerCase();
                        boolean titleMatch = node.getTitle() != null && node.getTitle().toLowerCase().contains(lower);
                        boolean textMatch = node.getContentText() != null && node.getContentText().toLowerCase().contains(lower);
                        if (!titleMatch && !textMatch) return false;
                    }
                    if (createdById != null && !createdById.isBlank()) {
                        if (!createdById.equals(node.getCreatedById())) return false;
                    }
                    if (from != null && node.getCreatedAt() != null && node.getCreatedAt().isBefore(from)) {
                        return false;
                    }
                    if (to != null && node.getCreatedAt() != null && node.getCreatedAt().isAfter(to)) {
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());

        return filtered.stream()
                .map(node -> {
                    List<String> breadcrumb = buildBreadcrumb(node, nodeMap);
                    String assessmentName = resolveAssessmentName(node, nodeMap);
                    NotebookNodeDto dto = NotebookNodeDto.fromEntity(node);
                    long childCount = notebookNodeRepository.countByParentIdAndDeletedAtIsNull(node.getId());
                    dto.setHasChildren(childCount > 0);
                    return NotebookSearchResultDto.builder()
                            .node(dto)
                            .breadcrumb(breadcrumb)
                            .assessmentName(assessmentName)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private List<String> buildBreadcrumb(NotebookNode node, Map<String, NotebookNode> nodeMap) {
        LinkedList<String> crumbs = new LinkedList<>();
        NotebookNode current = node;
        while (current != null) {
            crumbs.addFirst(current.getTitle());
            if (current.getParentId() == null) break;
            current = nodeMap.get(current.getParentId());
        }
        return new ArrayList<>(crumbs);
    }

    private String resolveAssessmentName(NotebookNode node, Map<String, NotebookNode> nodeMap) {
        // Walk up to the root
        NotebookNode current = node;
        while (current.getParentId() != null) {
            NotebookNode parent = nodeMap.get(current.getParentId());
            if (parent == null) break;
            current = parent;
        }
        // current is now the root
        if (current.getAssessmentId() != null) {
            return current.getTitle();
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // File upload / download
    // -------------------------------------------------------------------------

    /**
     * Allocate a file id and the backend URL the client streams the body to.
     * See {@code AssessmentService.prepareUpload} for why this no longer hands
     * out a storage URL.
     */
    public UploadTargetResponse prepareUpload(String nodeId, String fileName, String userId) {
        notebookNodeRepository.findByIdAndDeletedAtIsNull(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Notebook node not found: " + nodeId));

        String fileId = UUID.randomUUID().toString();
        String key = attachmentKey(nodeId, fileId, fileName);

        log.info("Prepared upload for notebook node {} file {} by user {}", nodeId, fileName, userId);
        return UploadTargetResponse.builder()
                .fileId(fileId)
                .uploadUrl(String.format("/api/v1/notebook/nodes/%s/files/%s/content", nodeId, fileId))
                .storageKey(key)
                .build();
    }

    /** Stream an uploaded body straight into storage under the allocated key. */
    public void storeUpload(String nodeId, String fileId, String fileName,
                            String contentType, long contentLength, InputStream body) {
        notebookNodeRepository.findByIdAndDeletedAtIsNull(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Notebook node not found: " + nodeId));
        storageService.uploadStream(attachmentKey(nodeId, fileId, fileName), body, contentLength, contentType);
    }

    private static String attachmentKey(String nodeId, String fileId, String fileName) {
        return String.format("notebooks/%s/%s/%s", nodeId, fileId, fileName);
    }

    /**
     * Confirm a completed upload by persisting the file metadata to the notebook node.
     */
    public NotebookAttachmentDto confirmFileUpload(
            String nodeId, String fileId, String fileName,
            String contentType, Long fileSize, String userId) {

        NotebookNode node = notebookNodeRepository.findByIdAndDeletedAtIsNull(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Notebook node not found: " + nodeId));

        String userName = resolveUserName(userId);
        String key = attachmentKey(nodeId, fileId, fileName);

        NotebookAttachment attachment = NotebookAttachment.builder()
                .id(fileId)
                .fileName(fileName)
                .contentType(contentType)
                .fileSize(fileSize)
                .storageKey(key)
                .uploadedById(userId)
                .uploadedByName(userName)
                .uploadedAt(LocalDateTime.now())
                .build();

        if (node.getAttachments() == null) {
            node.setAttachments(new ArrayList<>());
        }
        node.getAttachments().add(attachment);
        notebookNodeRepository.save(node);

        log.info("Confirmed upload of file {} ({}) to notebook node {}", fileName, fileId, nodeId);
        return NotebookAttachmentDto.fromEntity(attachment);
    }

    /**
     * Open an attachment's bytes for streaming to the client. The caller owns the
     * returned stream and must close it.
     */
    public StorageService.StoredFile openFile(String nodeId, String fileId) {
        NotebookNode node = notebookNodeRepository.findByIdAndDeletedAtIsNull(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Notebook node not found: " + nodeId));

        NotebookAttachment attachment = node.getAttachments().stream()
                .filter(a -> fileId.equals(a.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("File not found: " + fileId));

        return new StorageService.StoredFile(
                storageService.openStream(attachment.getStorageKey()), attachment.getFileName());
    }

    /**
     * Delete a file from storage and remove its metadata from the notebook node.
     */
    public void deleteFile(String nodeId, String fileId, String userId) {
        NotebookNode node = notebookNodeRepository.findByIdAndDeletedAtIsNull(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Notebook node not found: " + nodeId));

        NotebookAttachment attachment = node.getAttachments().stream()
                .filter(a -> fileId.equals(a.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("File not found: " + fileId));

        storageService.deleteObject(attachment.getStorageKey());
        node.getAttachments().removeIf(a -> fileId.equals(a.getId()));
        notebookNodeRepository.save(node);

        log.info("Deleted file {} from notebook node {} by user {}", fileId, nodeId, userId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String resolveUserName(String userId) {
        // userId is the JWT principal name (username), so search by username first
        return userRepository.findByUsername(userId).map(u -> {
            String name = ((u.getFirstName() != null ? u.getFirstName() : "") + " " +
                    (u.getLastName() != null ? u.getLastName() : "")).trim();
            return name.isEmpty() ? u.getUsername() : name;
        }).orElse(userId);
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "").trim();
    }

    /**
     * Records which inline images a note uses, so the nightly GC does not reap them.
     *
     * <p>{@link InlineImageGcJob} deletes any image nothing references once it is a day old, and
     * notes were never indexed — every screenshot pasted into the notebook was deleted the
     * following night. Shared-field indexing rather than the assessment-scoped kind: a note is
     * anchored to an application, and its screenshots can come from more than one assessment.
     */
    private void indexInlineImages(NotebookNode node) {
        if (node == null) return;
        inlineImageService.updateRefsForSharedField(noteImageFieldKey(node.getId()), node.getContent());
    }

    private static String noteImageFieldKey(String nodeId) {
        return "notebook/" + nodeId + "/content";
    }
}
