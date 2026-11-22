package com.xmu.ShopAssistant.memory;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

class MemoryMapperXmlTest {

    @Test
    void shouldParseAllMemoryMapperXmlFiles() {
        List<String> resources = List.of(
                "mapper/MemoryEventMapper.xml",
                "mapper/MemoryNoteMapper.xml",
                "mapper/MemoryRepresentationMapper.xml",
                "mapper/MemorySnapshotMapper.xml",
                "mapper/MemoryDreamMapper.xml"
        );

        assertThatCode(() -> {
            Configuration configuration = new Configuration();
            for (String resource : resources) {
                try (InputStream input = Resources.getResourceAsStream(resource)) {
                    new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
                }
            }
        }).doesNotThrowAnyException();
    }
}
