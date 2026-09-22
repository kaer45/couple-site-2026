package com.couple.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;

/**
 * Agent 集成配置：启用 @Async（ingest 监听器异步执行，不阻塞主流程）
 */
@Configuration
@EnableAsync
public class AgentConfig {

    @Bean
    public RestTemplate agentRestTemplate() {
        return new RestTemplate();
    }

    /** /ai 页 SSE 应答专用线程池（避免长时间流占用 Tomcat 工作线程） */
    @Bean(name = "agentExecutor")
    public Executor agentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("agent-sse-");
        executor.setAllowCoreThreadTimeOut(true);
        executor.initialize();
        return executor;
    }
}