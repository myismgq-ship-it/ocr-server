package com.gsafety.ocrtool.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
/**
 * 记录统一网关传入的调用方和请求标识，并写入 MDC 供整条调用链复用。
 *
 * <p>本过滤器只负责审计上下文；身份认证和限流仍由部署层统一网关完成。</p>
 */

@Component
public class GatewayAuditFilter extends OncePerRequestFilter {

    /** 网关请求链路标识；缺失或非法时由服务生成。 */
    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    /** 网关认证后的调用方标识；缺失时使用固定占位值。 */
    public static final String CALLER_ID_HEADER = "X-Caller-ID";
    private static final Logger log = LoggerFactory.getLogger(GatewayAuditFilter.class);

    /**
     * 建立请求审计上下文，调用下游过滤链，并在 finally 中清理线程变量。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        String requestId = safeHeader(request.getHeader(REQUEST_ID_HEADER), UUID.randomUUID().toString());
        String callerId = safeHeader(request.getHeader(CALLER_ID_HEADER), "gateway-unknown");
        // MDC 只在当前请求线程内有效，便于业务日志自动携带调用方和请求 ID。
        MDC.put("requestId", requestId);
        MDC.put("callerId", callerId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 无论接口成功还是抛出异常都记录审计日志并清理 MDC，避免线程复用污染下一请求。
            log.info(
                    "网关请求审计，callerId={}, requestId={}, method={}, path={}, status={}, durationMs={}",
                    callerId,
                    requestId,
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            MDC.remove("requestId");
            MDC.remove("callerId");
        }
    }

    private String safeHeader(String value, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String normalized = value.trim();
        if (normalized.length() > 128 || !normalized.matches("[A-Za-z0-9._:@/-]+")) {
            return fallback;
        }
        return normalized;
    }
}
