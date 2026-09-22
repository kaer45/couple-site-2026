package com.couple.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 业务数据入库事件（在业务方法事务内发布，提交后由监听器异步同步到 Python 向量库）
 * action: upsert = 新增/更新向量；delete = 删除向量
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IngestEvent {

    /** 业务类型：moment / photo（学习笔记后续复用同一通道） */
    private String bizType;

    /** 业务记录 ID */
    private Long bizId;

    /** 要向量化的文本（delete 时可空） */
    private String content;

    /** 数据域：couple=情侣共享 / personal=个人私有 */
    private String scope;

    /** 数据域 ID：共享域= coupleId，个人域= userId */
    private Long scopeId;

    /** upsert / delete */
    private String action;

    /** 事件产生时间（排查用） */
    private LocalDateTime occurredAt = LocalDateTime.now();

    public IngestEvent(String bizType, Long bizId, String content, String scope, Long scopeId, String action) {
        this.bizType = bizType;
        this.bizId = bizId;
        this.content = content;
        this.scope = scope;
        this.scopeId = scopeId;
        this.action = action;
    }
}