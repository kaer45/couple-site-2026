package com.couple.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 入库事件监听器
 *
 * - 事务提交后（AFTER_COMMIT）再调用 Python 的 /v1/ingest，避免事务回滚时同步出脏数据
 * - fallbackExecution=true：业务方法没有事务（如动态发布/删除）时也会立即执行
 * - @Async：异步执行，不阻塞业务请求线程
 */
@Component
@RequiredArgsConstructor
public class IngestEventListener {

    private final AgentClient agentClient;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onIngest(IngestEvent event) {
        if ("delete".equalsIgnoreCase(event.getAction())) {
            agentClient.delete(event.getBizType(), event.getBizId(),
                    event.getScope(), event.getScopeId());
        } else {
            agentClient.ingest(event.getBizType(), event.getBizId(),
                    event.getContent(), event.getScope(), event.getScopeId());
        }
    }
}