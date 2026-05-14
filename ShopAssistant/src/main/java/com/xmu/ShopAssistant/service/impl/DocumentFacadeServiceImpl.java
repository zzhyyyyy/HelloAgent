package com.xmu.ShopAssistant.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.xmu.ShopAssistant.converter.DocumentConverter;
import com.xmu.ShopAssistant.exception.BizException;
import com.xmu.ShopAssistant.mapper.DocumentMapper;
import com.xmu.ShopAssistant.model.dto.DocumentDTO;
import com.xmu.ShopAssistant.model.entity.Document;
import com.xmu.ShopAssistant.model.request.CreateDocumentRequest;
import com.xmu.ShopAssistant.model.request.UpdateDocumentRequest;
import com.xmu.ShopAssistant.model.response.CreateDocumentResponse;
import com.xmu.ShopAssistant.model.response.GetDocumentsResponse;
import com.xmu.ShopAssistant.model.vo.DocumentVO;
import com.xmu.ShopAssistant.mapper.ChunkBgeM3Mapper;
import com.xmu.ShopAssistant.model.entity.ChunkBgeM3;
import com.xmu.ShopAssistant.service.DocumentFacadeService;
import com.xmu.ShopAssistant.service.DocumentStorageService;
import com.xmu.ShopAssistant.service.MarkdownParserService;
import com.xmu.ShopAssistant.service.RagService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.xmu.ShopAssistant.model.response.DocumentStatusResponse;
import org.springframework.scheduling.annotation.Async;

@Service
@AllArgsConstructor
@Slf4j
public class DocumentFacadeServiceImpl implements DocumentFacadeService {
    private static final int TXT_CHUNK_SIZE = 800;
    private static final int TXT_CHUNK_OVERLAP = 120;
    private static final int PDF_CHUNK_SIZE = 1000;
    private static final int PDF_CHUNK_OVERLAP = 150;
    private static final long LARGE_FILE_THRESHOLD = 20 * 1024 * 1024; // 20MB

    private final DocumentMapper documentMapper;
    private final DocumentConverter documentConverter;
    private final DocumentStorageService documentStorageService;
    private final MarkdownParserService markdownParserService;
    private final RagService ragService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;

    @Override
    public GetDocumentsResponse getDocuments() {
        List<Document> documents = documentMapper.selectAll();
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public GetDocumentsResponse getDocumentsByKbId(String kbId) {
        List<Document> documents = documentMapper.selectByKbId(kbId);
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public CreateDocumentResponse createDocument(CreateDocumentRequest request) {
        try {
            // 将 CreateDocumentRequest 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(request);

            // 将 DocumentDTO 转换为 Document 实体
            Document document = documentConverter.toEntity(documentDTO);

            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，ID 由数据库自动生成
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档失败");
            }

            // 返回生成的 documentId
            return CreateDocumentResponse.builder()
                    .documentId(document.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建文档时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public CreateDocumentResponse uploadDocument(String kbId, MultipartFile file) {
        try {
            if (file.isEmpty()) {
                throw new BizException("上传的文件为空");
            }

            // 提取文件信息
            String originalFilename = file.getOriginalFilename();
            String filetype = getFileType(originalFilename);
            long fileSize = file.getSize();

            // 创建文档记录（先创建记录，获取 documentId）
            DocumentDTO documentDTO = DocumentDTO.builder()
                    .kbId(kbId)
                    .filename(originalFilename)
                    .filetype(filetype)
                    .size(fileSize)
                    .build();

            Document document = documentConverter.toEntity(documentDTO);
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，获取生成的 documentId
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档记录失败");
            }

            String documentId = document.getId();

            // 保存文件
            String filePath = documentStorageService.saveFile(kbId, documentId, file);

            // 更新文档记录，保存文件路径到 metadata
            DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
            metadata.setFilePath(filePath);
            documentDTO.setMetadata(metadata);
            documentDTO.setId(documentId);
            documentDTO.setCreatedAt(now);
            documentDTO.setUpdatedAt(now);

            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(documentId);
            updatedDocument.setCreatedAt(now);
            updatedDocument.setUpdatedAt(now);

            documentMapper.updateById(updatedDocument);

            log.info("文档上传成功: kbId={}, documentId={}, filename={}", kbId, documentId, originalFilename);

            // 大文件异步解析，小文件同步解析
            if (fileSize >= LARGE_FILE_THRESHOLD) {
                setDocumentStatus(documentId, "PROCESSING", null, null);
                processDocumentAsync(kbId, documentId, filePath, filetype);
                log.info("大文件进入异步解析: documentId={}, size={}", documentId, fileSize);
                return CreateDocumentResponse.builder()
                        .documentId(documentId)
                        .duration(0)
                        .processing(true)
                        .build();
            }

            long start = System.currentTimeMillis();
            processDocumentByType(kbId, documentId, filePath, filetype);
            long duration = System.currentTimeMillis() - start;

            log.info("文档解析完成: documentId={}, 用时 {} ms", documentId, duration);

            return CreateDocumentResponse.builder()
                    .documentId(documentId)
                    .duration(duration)
                    .processing(false)
                    .build();
        } catch (IOException e) {
            log.error("文件保存失败", e);
            throw new BizException("文件保存失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException("文档不存在: " + documentId);
        }

        // 删除文件
        try {
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            if (documentDTO.getMetadata() != null && documentDTO.getMetadata().getFilePath() != null) {
                String filePath = documentDTO.getMetadata().getFilePath();
                documentStorageService.deleteFile(filePath);
            }
        } catch (Exception e) {
            log.warn("删除文件失败，继续删除文档记录: documentId={}, error={}", documentId, e.getMessage());
            // 即使文件删除失败，也继续删除数据库记录
        }

        // 删除数据库记录
        int result = documentMapper.deleteById(documentId);
        if (result <= 0) {
            throw new BizException("删除文档失败");
        }
    }

    /**
     * 处理 Markdown 文档，解析并生成 chunks
     */
    private void processMarkdownDocument(String kbId, String documentId, String filePath) {
        try {
            log.info("开始处理 Markdown 文档: kbId={}, documentId={}, filePath={}", kbId, documentId, filePath);

            // 从保存的文件路径读取文件
            Path path = documentStorageService.getFilePath(filePath);
            try (InputStream inputStream = Files.newInputStream(path)) {
                // 解析 Markdown 文件
                List<MarkdownParserService.MarkdownSection> sections = markdownParserService.parseMarkdown(inputStream);

                System.out.println(sections);

                if (sections.isEmpty()) {
                    log.warn("Markdown 文档解析后没有找到任何章节: documentId={}", documentId);
                    return;
                }

                int chunkCount = persistChunksFromSections(kbId, documentId, sections);
                log.info("Markdown 文档处理完成: documentId={}, 共生成 {} 个 chunks", documentId, chunkCount);
            }
        } catch (Exception e) {
            // 不抛出异常，避免影响文档上传流程
        }
    }

    private void processTxtDocument(String kbId, String documentId, String filePath) {
        try {
            log.info("开始处理 TXT 文档: kbId={}, documentId={}, filePath={}", kbId, documentId, filePath);
            Path path = documentStorageService.getFilePath(filePath);
            String text = Files.readString(path, StandardCharsets.UTF_8);
            List<MarkdownParserService.MarkdownSection> sections = splitTextToSections(text, TXT_CHUNK_SIZE, TXT_CHUNK_OVERLAP, "TXT段落");
            int chunkCount = persistChunksFromSections(kbId, documentId, sections);
            log.info("TXT 文档处理完成: documentId={}, 共生成 {} 个 chunks", documentId, chunkCount);
        } catch (Exception e) {
            log.error("处理 TXT 文档失败: documentId={}", documentId, e);
        }
    }

    private void processPdfDocument(String kbId, String documentId, String filePath) {
        try {
            log.info("开始处理 PDF 文档: kbId={}, documentId={}, filePath={}", kbId, documentId, filePath);
            Path path = documentStorageService.getFilePath(filePath);
            StringBuilder allText = new StringBuilder();
            try (PDDocument document = Loader.loadPDF(path.toFile())) {
                PDFTextStripper stripper = new PDFTextStripper();
                int pages = document.getNumberOfPages();
                for (int page = 1; page <= pages; page++) {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    String pageText = stripper.getText(document);
                    if (StringUtils.hasText(pageText)) {
                        allText.append(pageText).append("\n");
                    }
                }
            }

            List<MarkdownParserService.MarkdownSection> sections = splitTextToSections(
                    allText.toString(),
                    PDF_CHUNK_SIZE,
                    PDF_CHUNK_OVERLAP,
                    "PDF段落"
            );
            int chunkCount = persistChunksFromSections(kbId, documentId, sections);
            log.info("PDF 文档处理完成: documentId={}, 共生成 {} 个 chunks", documentId, chunkCount);
        } catch (Exception e) {
            log.error("处理 PDF 文档失败: documentId={}", documentId, e);
        }
    }

    private void processDocumentByType(String kbId, String documentId, String filePath, String filetype) {
        if ("md".equalsIgnoreCase(filetype) || "markdown".equalsIgnoreCase(filetype)) {
            processMarkdownDocument(kbId, documentId, filePath);
            return;
        }
        if ("txt".equalsIgnoreCase(filetype)) {
            processTxtDocument(kbId, documentId, filePath);
            return;
        }
        if ("pdf".equalsIgnoreCase(filetype)) {
            processPdfDocument(kbId, documentId, filePath);
            return;
        }
        log.warn("暂不支持的文件类型: {}", filetype);
    }

    @Async
    public CompletableFuture<Void> processDocumentAsync(String kbId, String documentId, String filePath, String filetype) {
        long start = System.currentTimeMillis();
        try {
            processDocumentByType(kbId, documentId, filePath, filetype);
            long duration = System.currentTimeMillis() - start;
            setDocumentStatus(documentId, "COMPLETED", duration, null);
            log.info("异步文档解析完成: documentId={}, 用时 {} ms", documentId, duration);
        } catch (Exception e) {
            log.error("异步文档解析失败: documentId={}", documentId, e);
            setDocumentStatus(documentId, "FAILED", null, e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public DocumentStatusResponse getDocumentStatus(String documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException("文档不存在: " + documentId);
        }
        try {
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            DocumentDTO.MetaData metadata = documentDTO.getMetadata();
            if (metadata == null || metadata.getStatus() == null) {
                return DocumentStatusResponse.builder()
                        .status("COMPLETED")
                        .duration(0L)
                        .build();
            }
            return DocumentStatusResponse.builder()
                    .status(metadata.getStatus())
                    .duration(metadata.getDuration())
                    .errorMessage(metadata.getErrorMessage())
                    .build();
        } catch (Exception e) {
            throw new BizException("获取文档状态失败: " + e.getMessage());
        }
    }

    private void setDocumentStatus(String documentId, String status, Long duration, String errorMessage) {
        try {
            Document document = documentMapper.selectById(documentId);
            if (document == null) return;

            DocumentDTO dto = documentConverter.toDTO(document);
            DocumentDTO.MetaData metadata = dto.getMetadata();
            if (metadata == null) {
                metadata = new DocumentDTO.MetaData();
            }
            metadata.setStatus(status);
            metadata.setDuration(duration);
            metadata.setErrorMessage(errorMessage);
            dto.setMetadata(metadata);

            Document updated = documentConverter.toEntity(dto);
            updated.setId(documentId);
            updated.setCreatedAt(document.getCreatedAt());
            documentMapper.updateById(updated);
        } catch (Exception e) {
            log.error("更新文档状态失败: documentId={}, status={}", documentId, status, e);
        }
    }

    private int persistChunksFromSections(String kbId, String documentId, List<MarkdownParserService.MarkdownSection> sections) {
        if (sections == null || sections.isEmpty()) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        int chunkCount = 0;
        for (MarkdownParserService.MarkdownSection section : sections) {
            String title = section.getTitle();
            String content = section.getContent();
            if (!StringUtils.hasText(content)) {
                continue;
            }

            // 对标题+内容前缀进行向量化，兼顾主题词与正文语义
            String embeddingText = StringUtils.hasText(title)
                    ? title + "\n" + truncate(content, 300)
                    : truncate(content, 300);
            float[] embedding = ragService.embed(embeddingText);

            ChunkBgeM3 chunk = ChunkBgeM3.builder()
                    .kbId(kbId)
                    .docId(documentId)
                    .content(content)
                    .metadata(title)
                    .embedding(embedding)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            int result = chunkBgeM3Mapper.insert(chunk);
            if (result > 0) {
                chunkCount++;
            }
        }
        return chunkCount;
    }

    private List<MarkdownParserService.MarkdownSection> splitTextToSections(
            String text,
            int chunkSize,
            int overlap,
            String titlePrefix
    ) {
        List<MarkdownParserService.MarkdownSection> sections = new ArrayList<>();
        String sanitizedInput = sanitizeForPostgres(text);
        if (!StringUtils.hasText(sanitizedInput)) {
            return sections;
        }
        String normalized = sanitizedInput.replace("\r\n", "\n").replace("\r", "\n").trim();
        if (!StringUtils.hasText(normalized)) {
            return sections;
        }

        int start = 0;
        int idx = 1;
        while (start < normalized.length()) {
            int end = Math.min(start + chunkSize, normalized.length());
            String chunk = normalized.substring(start, end).trim();
            if (StringUtils.hasText(chunk)) {
                sections.add(new MarkdownParserService.MarkdownSection(titlePrefix + idx, chunk));
                idx++;
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return sections;
    }

    private String truncate(String text, int maxLen) {
        String sanitized = sanitizeForPostgres(text);
        if (!StringUtils.hasText(sanitized)) {
            return "";
        }
        if (sanitized.length() <= maxLen) {
            return sanitized;
        }
        return sanitized.substring(0, maxLen);
    }

    /**
     * PostgreSQL text/jsonb 不允许包含 0x00；同时清理不可见控制字符（保留换行/制表符）
     */
    private String sanitizeForPostgres(String text) {
        if (text == null) {
            return null;
        }
        return text
                .replace("\u0000", "")
                .replaceAll("[\\x01-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
    }

    /**
     * 从文件名提取文件类型
     */
    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    @Override
    public void updateDocument(String documentId, UpdateDocumentRequest request) {
        try {
            // 查询现有的文档
            Document existingDocument = documentMapper.selectById(documentId);
            if (existingDocument == null) {
                throw new BizException("文档不存在: " + documentId);
            }

            // 将现有 Document 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(existingDocument);

            // 使用 UpdateDocumentRequest 更新 DocumentDTO
            documentConverter.updateDTOFromRequest(documentDTO, request);

            // 将更新后的 DocumentDTO 转换回 Document 实体
            Document updatedDocument = documentConverter.toEntity(documentDTO);

            // 保留原有的 ID、kbId 和创建时间
            updatedDocument.setId(existingDocument.getId());
            updatedDocument.setKbId(existingDocument.getKbId());
            updatedDocument.setCreatedAt(existingDocument.getCreatedAt());
            updatedDocument.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = documentMapper.updateById(updatedDocument);
            if (result <= 0) {
                throw new BizException("更新文档失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新文档时发生序列化错误: " + e.getMessage());
        }
    }
}
