package com.xmu.ShopAssistant.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MemorySnapshotMapper {
    int insert(MemorySnapshot snapshot);

    int updateStatus(
            @Param("id") String id,
            @Param("status") MemorySnapshotStatus status
    );

    MemorySnapshot selectById(@Param("id") String id);

    MemorySnapshot selectActive(
            @Param("tenantId") String tenantId,
            @Param("ownerId") String ownerId,
            @Param("agentId") String agentId
    );

    int insertItem(MemorySnapshotItem item);

    List<MemorySnapshotItem> selectItems(@Param("snapshotId") String snapshotId);
}
