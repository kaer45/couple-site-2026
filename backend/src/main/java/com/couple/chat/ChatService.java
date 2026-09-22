package com.couple.chat;

import com.couple.chat.dto.MessageVO;

import java.util.List;

/**
 * 聊天服务接口
 */
public interface ChatService {

    /**
     * 保存消息（幂等：撞 (couple_id, client_msg_id) 唯一键直接丢弃返回 null）
     *
     * @return 落库成功的消息 VO；命中幂等键返回 null（不重复入库、不重复广播）
     */
    MessageVO save(Long coupleId, Long senderId, String type, String content,
                   String clientMsgId, boolean botFlag);

    /**
     * 历史消息（按 id 倒序，游标分页）
     *
     * @param beforeId null=取最新，否则取 id < beforeId
     */
    List<MessageVO> history(Long coupleId, Long beforeId, int size);

    /**
     * 增量消息（按 id 正序，断线重连/新消息拉取）
     */
    List<MessageVO> after(Long coupleId, Long afterId, int limit);

    /** 上报已读位点（GREATEST 只增不减） */
    void markRead(Long coupleId, Long userId, Long lastReadMessageId);

    /** 未读数 = 对方发给我的、id > 已读位点的消息数 */
    long unreadCount(Long coupleId, Long userId);
}