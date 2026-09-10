package com.couple.config;

import com.couple.auth.JwtUtil;
import com.couple.user.entity.User;
import com.couple.user.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT 认证过滤器：解析 Authorization: Bearer xxx，
 * 校验通过后把 CoupleUserPrincipal 放入 SecurityContext；
 * 解析失败或没有 token 时直接放行，由 AuthenticationEntryPoint 兜底返回 401
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtil.parseToken(header.substring(7));
                Long userId = Long.valueOf(claims.getSubject());
                User user = userMapper.selectById(userId);
                if (user != null) {
                    CoupleUserPrincipal principal =
                            new CoupleUserPrincipal(user.getId(), user.getUsername(), user.getCoupleId());
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception ignored) {
                // token 无效：不设置认证信息，后续由 EntryPoint 返回 401
            }
        }
        filterChain.doFilter(request, response);
    }
}
