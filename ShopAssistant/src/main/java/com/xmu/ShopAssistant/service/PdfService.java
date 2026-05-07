package com.xmu.ShopAssistant.service;

public interface PdfService {

    byte[] generatePdf(String title, String content, String sessionTitle);

    byte[] generatePdf(String title, String content, String sessionTitle, String createdAt);
}
