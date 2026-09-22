package com.couple.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.couple.chat.dto.MessageVO;
import com.couple.chat.entity.ChatMessage;
import com.couple.chat.mapper.ChatMessageMapper;
import com.couple.chat.mapper.ChatReadMapper;
import com.couple.couple.entity.Couple;
import com.couple.couple.mapper.CoupleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * 聊天服务实现
 * 关键点：
 * - 幂等：client_msg_id 撞唯一键 → DataIntegrityViolationException → 丢弃
 * - 排序：全链路按自增 id（WS 广播与历史拉取同序）
 * - 已读：发送成功后发送者位点自动推进到新消息 id（我发的必然看过）
 */
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final int DEFAULT_HISTORY_SIZE = 20;
    private static final int DEFAULT_AFTER_LIMIT = 200;

    private final ChatMessageMapper messageMapper;
    private final ChatReadMapper readMapper;
    private final CoupleMapper coupleMapper;

    @Override
    public MessageVO save(Long coupleId, Long senderId, String type, String content,
                          String clientMsgId, boolean botFlag) {
        if (!StringUtils.hasText(content) || coupleId == null || senderId == null) {
            return null;
        }
        ChatMessage msg = new ChatMessage();
        msg.setCoupleId(coupleId);
        msg.setSenderId(senderId);
        msg.setType(normalizeType(type));
        msg.setContent(content.trim());
        msg.setBotFlag(botFlag);
        msg.setClientMsgId(StringUtils.hasText(clientMsgId) ? clientMsgId : null);
        try {
            messageMapper.insert(msg);
        } catch (DataIntegrityViolationException e) {
            // 唯一键 (couple_id, client_msg_id) 冲突：同一条消息重复提交，直接丢弃
            return null;
        }
        // 发送者已读位推进：仅普通消息（bot 触发/AGENT 回复不推进，它们是辅助内容）
        if (msg.getId() != null && !Boolean.TRUE.equals(msg.getBotFlag())) {
            readMapper.upsertLastRead(coupleId, senderId, msg.getId());
        }
        return toVO(msg);
    }

    @Override
    public List<MessageVO> history(Long coupleId, Long beforeId, int size) {
        int limit = size > 0 ? Math.min(size, 100) : DEFAULT_HISTORY_SIZE;
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getCoupleId, coupleId)
                .lt(beforeId != null, ChatMessage::getId, beforeId)
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT " + limit);
        List<ChatMessage> rows = messageMapper.selectList(wrapper);
        return rows.stream().map(this::toVO).toList();
    }

    @Override
    public List<MessageVO> after(Long coupleId, Long afterId, int limit) {
        if (afterId == null) {
            return Collections.emptyList();
        }
        int max = limit > 0 ? Math.min(limit, DEFAULT_AFTER_LIMIT) : DEFAULT_AFTER_LIMIT;
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getCoupleId, coupleId)
                .gt(ChatMessage::getId, afterId)
                .orderByAsc(ChatMessage::getId)
                .last("LIMIT " + max);
        List<ChatMessage> rows = messageMapper.selectList(wrapper);
        return rows.stream().map(this::toVO).toList();
    }

    @Override
    public void markRead(Long coupleId, Long userId, Long lastReadMessageId) {
        if (coupleId == null || userId == null || lastReadMessageId == null) {
            return;
        }
        readMapper.upsertLastRead(coupleId, userId, lastReadMessageId);
    }

    @Override
    public long unreadCount(Long coupleId, Long userId) {
        if (coupleId == null || userId == null) {
            return 0;
        }
        Long lastRead = readMapper.selectLastRead(coupleId, userId);
        long lastReadId = lastRead == null ? 0L : lastRead;
        // 对方 = couple 中除我之外的另一个用户
        Couple couple = coupleMapper.selectById(coupleId);
        if (couple == null) {
            return 0;
        }
        Long otherId = userId.equals(couple.getUserAId()) ? couple.getUserBId() : couple.getUserAId();
        // 未读 = 对方发的、id 大于已读位点的消息数（自己发的始终视为已读）
        Long count = messageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getCoupleId, coupleId)
                .eq(ChatMessage::getSenderId, otherId)
                .gt(ChatMessage::getId, lastReadId));
        return count == null ? 0 : count;
    }

    /** 归一化类型：不合法一律按 TEXT */
    private String normalizeType(String type) {
        if ("IMAGE".equalsIgnoreCase(type) || "AGENT".equalsIgnoreCase(type)) {
            return type.toUpperCase();
        }
        return "TEXT";
    }

    private MessageVO toVO(ChatMessage msg) {
        MessageVO vo = new MessageVO();
        vo.setId(msg.getId());
        vo.setSenderId(msg.getSenderId());
        vo.setType(msg.getType());
        vo.setContent(msg.getContent());
        vo.setClientMsgId(msg.getClientMsgId());
        vo.setBotFlag(msg.getBotFlag());
        vo.setCreatedAt(msg.getCreatedAt());
        return vo;
    }
}