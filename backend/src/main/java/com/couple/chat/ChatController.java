package com.couple.chat;

import com.couple.chat.dto.MessageVO;
import com.couple.chat.dto.ReadRequest;
import com.couple.common.BusinessException;
import com.couple.common.Result;
import com.couple.config.CoupleUserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 聊天 REST 接口（历史/增量/已读/未读数）
 * 实时收发走 WebSocket /ws/chat，本控制器只承担拉取与状态
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private static final int DEFAULT_SIZE = 20;

    private final ChatService chatService;

    /** 历史消息：GET /api/chat/messages?beforeId=&size= （不含 afterId 则走此接口） */
    @GetMapping("/messages")
    public Result<List<MessageVO>> history(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                           @RequestParam(required = false) Long beforeId,
                                           @RequestParam(defaultValue = "20") Integer size) {
        return Result.ok(chatService.history(requiredCoupleId(principal), beforeId, size));
    }

    /** 增量消息：GET /api/chat/messages/after?afterId= （断线重连/新消息拉取） */
    @GetMapping("/messages/after")
    public Result<List<MessageVO>> after(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                         @RequestParam Long afterId,
                                         @RequestParam(defaultValue = "200") Integer limit) {
        return Result.ok(chatService.after(requiredCoupleId(principal), afterId, limit));
    }

    /** 上报已读位点：PATCH /api/chat/read */
    @PatchMapping("/read")
    public Result<Void> markRead(@AuthenticationPrincipal CoupleUserPrincipal principal,
                                 @Valid @RequestBody ReadRequest request) {
        chatService.markRead(requiredCoupleId(principal), principal.id(), request.getLastReadMessageId());
        return Result.ok();
    }

    /** 未读数：GET /api/chat/unread-count */
    @GetMapping("/unread-count")
    public Result<Map<String, Object>> unreadCount(@AuthenticationPrincipal CoupleUserPrincipal principal) {
        long unread = chatService.unreadCount(requiredCoupleId(principal), principal.id());
        return Result.ok(Map.of("unread", unread));
    }

    /** 未绑定情侣关系时禁止聊天 */
    private Long requiredCoupleId(CoupleUserPrincipal principal) {
        if (principal == null || principal.coupleId() == null) {
            throw new BusinessException(403, "未绑定情侣关系，无法聊天");
        }
        return principal.coupleId();
    }
}