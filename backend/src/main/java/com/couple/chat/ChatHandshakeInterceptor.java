package com.couple.chat;

import com.couple.auth.JwtUtil;
import com.couple.user.entity.User;
import com.couple.user.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * 聊天握手拦截器：从 query ?token= 解析 JWT 鉴权
 * 浏览器原生 WebSocket 无法自定义 header，token 只能走 query（部署层自行对访问日志脱敏）
 * 校验通过后把 userId/coupleId 绑定到 session attributes，连接建立后不再依赖 token
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTR_USER_ID = "userId";
    static final String ATTR_COUPLE_ID = "coupleId";
    static final String ATTR_LAST_ACTIVE = "lastActive";

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        try {
            String token = UriComponentsBuilder.fromUri(request.getURI()).build()
                    .getQueryParams().getFirst("token");
            if (!StringUtils.hasText(token)) {
                return false;
            }
            Claims claims = jwtUtil.parseToken(token);
            Long userId = Long.valueOf(claims.getSubject());
            User user = userMapper.selectById(userId);
            if (user == null || user.getCoupleId() == null) {
                return false; // 用户不存在或未绑定：拒绝聊天
            }
            // 取库里最新的 coupleId（避免 JWT 里可能是旧值）
            attributes.put(ATTR_USER_ID, user.getId());
            attributes.put(ATTR_COUPLE_ID, user.getCoupleId());
            attributes.put(ATTR_LAST_ACTIVE, System.currentTimeMillis());
            return true;
        } catch (Exception e) {
            log.warn("聊天握手鉴权失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }
}