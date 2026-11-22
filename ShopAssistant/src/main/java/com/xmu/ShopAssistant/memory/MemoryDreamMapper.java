package com.xmu.ShopAssistant.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MemoryDreamMapper {
    int insertJob(MemoryDreamJob job);

    int updateJob(MemoryDreamJob job);

    MemoryDreamJob selectJob(@Param("id") String id);

    int insertProposal(MemoryChangeProposal proposal);

    int updateProposalStatus(
            @Param("id") String id,
            @Param("status") MemoryProposalStatus status
    );

    List<MemoryChangeProposal> selectProposals(@Param("snapshotId") String snapshotId);
}
