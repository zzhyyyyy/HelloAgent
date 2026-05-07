package com.xmu.ShopAssistant.service.impl;

import com.xmu.ShopAssistant.service.PdfService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class PdfServiceImpl implements PdfService {

    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float MARGIN = 50;
    private static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;
    private static final float TITLE_FONT_SIZE = 18;
    private static final float META_FONT_SIZE = 10;
    private static final float BODY_FONT_SIZE = 11;
    private static final float LINE_SPACING = 1.5f;
    private static final float BODY_LEADING = BODY_FONT_SIZE * LINE_SPACING;
    private static final float PAGE_NUMBER_FONT_SIZE = 9;

    @Value("${pdf.font.path:}")
    private String configuredFontPath;

    @Value("${pdf.font.ttc-index:0}")
    private int ttcFontIndex;

    @Override
    public byte[] generatePdf(String title, String content, String sessionTitle) {
        return generatePdf(title, content, sessionTitle, null);
    }

    @Override
    public byte[] generatePdf(String title, String content, String sessionTitle, String createdAt) {
        try (PDDocument document = new PDDocument()) {
            PDFont font = resolveFont(document);
            String dateStr = createdAt != null
                    ? createdAt
                    : LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            List<String> bodyLines = wrapText(content, font, BODY_FONT_SIZE, CONTENT_WIDTH);

            float yStart = PAGE_HEIGHT - MARGIN;
            float yPosition = yStart;

            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(document, page);
            int pageNum = 1;

            // --- Title ---
            cs.beginText();
            cs.setFont(font, TITLE_FONT_SIZE);
            cs.newLineAtOffset(MARGIN, yPosition - TITLE_FONT_SIZE);
            cs.showText(title);
            cs.endText();
            yPosition -= (TITLE_FONT_SIZE * 1.8f);

            // --- Metadata ---
            cs.beginText();
            cs.setFont(font, META_FONT_SIZE);
            cs.newLineAtOffset(MARGIN, yPosition - META_FONT_SIZE);
            cs.showText("生成时间: " + dateStr);
            cs.endText();
            yPosition -= (META_FONT_SIZE * 1.8f);

            if (StringUtils.hasText(sessionTitle)) {
                cs.beginText();
                cs.setFont(font, META_FONT_SIZE);
                cs.newLineAtOffset(MARGIN, yPosition - META_FONT_SIZE);
                cs.showText("会话: " + sessionTitle);
                cs.endText();
                yPosition -= (META_FONT_SIZE * 1.8f);
            }

            // --- Separator ---
            yPosition -= 6;
            cs.setLineWidth(0.5f);
            cs.moveTo(MARGIN, yPosition);
            cs.lineTo(PAGE_WIDTH - MARGIN, yPosition);
            cs.stroke();
            yPosition -= 16;

            // --- Body text ---
            int lineIndex = 0;
            while (lineIndex < bodyLines.size()) {
                float nextY = yPosition - BODY_LEADING;
                if (nextY < MARGIN + BODY_FONT_SIZE) {
                    // Page footer (page number)
                    String pageFooter = "- " + pageNum + " -";
                    float footerWidth = font.getStringWidth(pageFooter) / 1000f * PAGE_NUMBER_FONT_SIZE;
                    cs.beginText();
                    cs.setFont(font, PAGE_NUMBER_FONT_SIZE);
                    cs.newLineAtOffset((PAGE_WIDTH - footerWidth) / 2, MARGIN - PAGE_NUMBER_FONT_SIZE - 6);
                    cs.showText(pageFooter);
                    cs.endText();
                    cs.close();

                    // New page
                    pageNum++;
                    page = new PDPage(PDRectangle.A4);
                    document.addPage(page);
                    cs = new PDPageContentStream(document, page);
                    yPosition = yStart;
                    continue;
                }

                String line = bodyLines.get(lineIndex);
                cs.beginText();
                cs.setFont(font, BODY_FONT_SIZE);
                cs.newLineAtOffset(MARGIN, yPosition - BODY_FONT_SIZE);
                cs.showText(line);
                cs.endText();
                yPosition = nextY;
                lineIndex++;
            }

            // Last page footer
            String pageFooter = "- " + pageNum + " -";
            float footerWidth = font.getStringWidth(pageFooter) / 1000f * PAGE_NUMBER_FONT_SIZE;
            cs.beginText();
            cs.setFont(font, PAGE_NUMBER_FONT_SIZE);
            cs.newLineAtOffset((PAGE_WIDTH - footerWidth) / 2, MARGIN - PAGE_NUMBER_FONT_SIZE - 6);
            cs.showText(pageFooter);
            cs.endText();
            cs.close();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            log.error("PDF 生成失败", e);
            throw new RuntimeException("PDF 生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 加载支持中文的字体。按优先级：
     * 1) 配置的字体路径 (pdf.font.path)
     * 2) 常见系统路径 (Windows → macOS → Linux)
     * 3) 回退到 PDFBox 内置字体（不支持中文，但不会崩溃）
     */
    private PDFont resolveFont(PDDocument document) {
        // 1) 配置路径
        if (StringUtils.hasText(configuredFontPath)) {
            PDFont font = tryLoadFont(document, configuredFontPath);
            if (font != null) return font;
            log.warn("配置的字体路径无效: {}, 尝试自动探测", configuredFontPath);
        }

        // 2) 系统路径探测
        String[] candidatePaths = detectSystemFontPaths();
        for (String path : candidatePaths) {
            PDFont font = tryLoadFont(document, path);
            if (font != null) {
                log.info("使用系统字体: {}", path);
                return font;
            }
        }

        // 3) classpath 内置字体（如果打包了的话）
        PDFont font = tryLoadBuiltinFont(document);
        if (font != null) return font;

        // 4) 回退：使用 PDFBox 内置标准字体（不渲染中文，但不崩溃）
        log.warn("未找到中文字体，PDF 中文将无法正常显示");
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    private String[] detectSystemFontPaths() {
        String os = System.getProperty("os.name").toLowerCase();
        List<String> paths = new ArrayList<>();

        if (os.contains("win")) {
            String windir = System.getenv("WINDIR");
            String fontsDir = (windir != null ? windir : "C:/Windows") + "/Fonts";
            paths.add(fontsDir + "/msyh.ttc");       // Microsoft YaHei
            paths.add(fontsDir + "/msyh.ttf");        // Microsoft YaHei (non-collection)
            paths.add(fontsDir + "/simsun.ttc");      // SimSun
            paths.add(fontsDir + "/simsun.ttf");
            paths.add(fontsDir + "/simhei.ttf");      // SimHei
            paths.add(fontsDir + "/yahei.ttf");
            paths.add(fontsDir + "/arial.ttf");
            paths.add(fontsDir + "/arialuni.ttf");
        } else if (os.contains("mac")) {
            paths.add("/System/Library/Fonts/PingFang.ttc");
            paths.add("/System/Library/Fonts/STHeiti Light.ttc");
            paths.add("/System/Library/Fonts/STHeiti Medium.ttc");
        } else {
            paths.add("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc");
            paths.add("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc");
            paths.add("/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc");
            paths.add("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc");
            paths.add("/usr/share/fonts/wqy-microhei.ttc");
        }
        return paths.toArray(new String[0]);
    }

    private PDFont tryLoadFont(PDDocument document, String path) {
        try {
            File fontFile = new File(path);
            if (!fontFile.exists() || !fontFile.isFile()) {
                return null;
            }
            try (FileInputStream fis = new FileInputStream(fontFile)) {
                return PDType0Font.load(document, fis);
            }
        } catch (IOException e) {
            log.debug("字体加载失败: {}", path, e);
            return null;
        }
    }

    private PDFont tryLoadBuiltinFont(PDDocument document) {
        // Try loading NotoSansSC-Regular.otf from classpath if bundled
        try (InputStream is = PdfServiceImpl.class.getResourceAsStream("/fonts/NotoSansSC-Regular.otf")) {
            if (is != null) {
                log.info("使用内置字体 NotoSansSC");
                return PDType0Font.load(document, is);
            }
        } catch (IOException e) {
            log.debug("内置字体加载失败", e);
        }
        return null;
    }

    /**
     * 手动逐字换行，兼容中英文混排。
     * 以换行符分割段落，对每个段落按字符宽度逐字换行。
     */
    private List<String> wrapText(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            lines.add("");
            return lines;
        }

        String[] paragraphs = text.split("\n");
        for (int p = 0; p < paragraphs.length; p++) {
            String paragraph = paragraphs[p];
            if (p > 0) {
                lines.add(""); // 空行表示段落分隔
            }
            if (!StringUtils.hasText(paragraph)) {
                continue;
            }

            // 逐字构建行（遍历每个字符，累计宽度）
            StringBuilder currentLine = new StringBuilder();
            for (int i = 0; i < paragraph.length(); i++) {
                char c = paragraph.charAt(i);
                String nextChar = String.valueOf(c);
                String candidate = currentLine + nextChar;
                float width = font.getStringWidth(candidate) / 1000f * fontSize;
                if (width > maxWidth && currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                    currentLine = new StringBuilder();
                }
                // 跳过行首空白（缩进保留一个空格）
                if (currentLine.isEmpty() && c == ' ' && !lines.isEmpty()) {
                    continue;
                }
                currentLine.append(c);
            }
            if (currentLine.length() > 0) {
                lines.add(currentLine.toString());
            }
        }
        return lines;
    }
}
