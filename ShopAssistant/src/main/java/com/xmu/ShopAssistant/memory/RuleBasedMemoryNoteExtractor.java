package com.xmu.ShopAssistant.memory;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高精度、低召回的确定性抽取器。只抽取用户明确自述，不处理工具输出和模型推断。
 */
@Component
public class RuleBasedMemoryNoteExtractor implements MemoryNoteExtractor {

    private static final Pattern IDENTITY = Pattern.compile("^我(?:是|是一名|是一位)(.{1,60})$");
    private static final Pattern ATTRIBUTE = Pattern.compile("^我的(.{1,20})是(.{1,60})$");
    private static final Pattern LIKE = Pattern.compile("^我(?:最)?(?:喜欢|偏好|爱)(.{1,40})$");
    private static final Pattern DISLIKE = Pattern.compile("^我(?:最)?(?:讨厌|不喜欢|厌恶|不爱)(.{1,40})$");

    @Override
    public List<ExtractedMemoryNote> extract(MemoryEvent event) {
        if (event == null
                || event.getEventType() != MemoryEventType.USER_MESSAGE
                || event.getTrustLevel() != MemoryTrustLevel.USER_ASSERTED
                || !StringUtils.hasText(event.getContent())) {
            return List.of();
        }

        List<ExtractedMemoryNote> notes = new ArrayList<>();
        for (String raw : event.getContent().split("[。！？；;，,\\n]")) {
            String sentence = raw == null ? "" : raw.trim();
            if (sentence.isEmpty() || sentence.length() > 120) continue;

            Matcher identity = IDENTITY.matcher(sentence);
            if (identity.matches()) {
                notes.add(note(MemoryNoteType.PROFILE, "profile.identity", sentence,
                        "{\"value\":\"" + escape(identity.group(1).trim()) + "\"}", 0.86));
                continue;
            }
            Matcher attribute = ATTRIBUTE.matcher(sentence);
            if (attribute.matches()) {
                String key = normalizeKey(attribute.group(1));
                notes.add(note(MemoryNoteType.PROFILE, "profile.attribute." + key, sentence,
                        "{\"name\":\"" + escape(attribute.group(1).trim())
                                + "\",\"value\":\"" + escape(attribute.group(2).trim()) + "\"}", 0.90));
                continue;
            }
            Matcher dislike = DISLIKE.matcher(sentence);
            if (dislike.matches()) {
                String target = dislike.group(1).trim();
                notes.add(note(MemoryNoteType.PREFERENCE, "preference." + normalizeKey(target), sentence,
                        "{\"target\":\"" + escape(target) + "\",\"sentiment\":\"dislike\"}", 0.90));
                continue;
            }
            Matcher like = LIKE.matcher(sentence);
            if (like.matches()) {
                String target = like.group(1).trim();
                notes.add(note(MemoryNoteType.PREFERENCE, "preference." + normalizeKey(target), sentence,
                        "{\"target\":\"" + escape(target) + "\",\"sentiment\":\"like\"}", 0.90));
            }
        }
        return notes;
    }

    private ExtractedMemoryNote note(
            MemoryNoteType type, String subject, String content, String structured, double confidence) {
        return new ExtractedMemoryNote(MemoryScope.USER, type, subject, content, structured,
                confidence, 0.8, "RULE_BASED");
    }

    private String normalizeKey(String value) {
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("^-|-$", "");
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
