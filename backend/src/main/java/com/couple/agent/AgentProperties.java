package com.couple.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent（Python AI 服务）集成配置
 * enabled 默认 false：Python 未就绪时完全静默，不影响主业务
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** 是否启用 Agent 集成（ingest/chat） */
    private boolean enabled = false;

    /** Python 服务根地址，如 http://localhost:8001 */
    private String url = "http://localhost:8001";

    /** Java↔Python 共享密钥（内网调用鉴权） */
    private String key = "change-me";
}