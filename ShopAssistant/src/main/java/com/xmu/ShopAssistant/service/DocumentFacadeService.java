package com.xmu.ShopAssistant.service;

import com.xmu.ShopAssistant.model.request.CreateDocumentRequest;
import com.xmu.ShopAssistant.model.request.UpdateDocumentRequest;
import com.xmu.ShopAssistant.model.response.CreateDocumentResponse;
import com.xmu.ShopAssistant.model.response.DocumentStatusResponse;
import com.xmu.ShopAssistant.model.response.GetDocumentsResponse;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentFacadeService {
    GetDocumentsResponse getDocuments();

    GetDocumentsResponse getDocumentsByKbId(String kbId);

    CreateDocumentResponse createDocument(CreateDocumentRequest request);

    CreateDocumentResponse uploadDocument(String kbId, MultipartFile file);

    void deleteDocument(String documentId);

    void updateDocument(String documentId, UpdateDocumentRequest request);

    DocumentStatusResponse getDocumentStatus(String documentId);
}
