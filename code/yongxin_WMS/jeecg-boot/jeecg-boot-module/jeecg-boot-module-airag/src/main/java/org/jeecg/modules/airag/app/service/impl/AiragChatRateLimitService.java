package org.jeecg.modules.airag.app.service.impl;

import jakarta.servlet.http.HttpServletRequest;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.util.IpUtils;
import org.jeecg.config.AiChatConfig;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * AI聊天匿名访问限流服务
 * 用于防止 /airag/chat/send、/airag/chat/upload 被恶意刷接口
 *
 * @author scott
 * @date 2026-07-20
 */
@Service
public class AiragChatRateLimitService {

	private final RedisTemplate redisTemplate;
	private final AiChatConfig.RateLimitConfig rateLimit;

	private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
	private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHH");

	/**
	 * 构造 AI 聊天助手访问限流服务
	 *
	 * @param redisTemplate Redis 操作模板
	 * @param aiChatConfig AI 聊天配置
	 * @author scott
	 * @since 2026-09-01 issues/9871 AI聊天助手访问限流配置优化
	 */
	public AiragChatRateLimitService(RedisTemplate redisTemplate, AiChatConfig aiChatConfig) {
		this.redisTemplate = redisTemplate;
		this.rateLimit = aiChatConfig.getRateLimit();
	}

	/**
	 * 校验 /airag/chat/send 调用频次
	 *
	 * @param request HttpServletRequest
	 */
	public void checkSendLimit(HttpServletRequest request) {
		String sessionId = request.getSession().getId();
		String clientIp = IpUtils.getIpAddr(request);
		String minute = LocalDateTime.now().format(MINUTE_FORMAT);

		checkLimit("airag:rate:send:session:" + sessionId + ":" + minute, rateLimit.getSendPerSessionPerMinute(), 120,
			"发送消息过于频繁，请稍后再试");
		checkLimit("airag:rate:send:ip:" + clientIp + ":" + minute, rateLimit.getSendPerIpPerMinute(), 120,
			"发送消息过于频繁，请稍后再试");
	}

	/**
	 * 校验 /airag/chat/upload 调用频次
	 *
	 * @param request HttpServletRequest
	 */
	public void checkUploadLimit(HttpServletRequest request) {
		String sessionId = request.getSession().getId();
		String clientIp = IpUtils.getIpAddr(request);
		String hour = LocalDateTime.now().format(HOUR_FORMAT);

		checkLimit("airag:rate:upload:session:" + sessionId + ":" + hour, rateLimit.getUploadPerSessionPerHour(), 7200,
			"上传文件过于频繁，请稍后再试");
		checkLimit("airag:rate:upload:ip:" + clientIp + ":" + hour, rateLimit.getUploadPerSessionPerHour(), 7200,
			"上传文件过于频繁，请稍后再试");
	}

	/**
	 * 固定窗口计数限流
	 *
	 * @param key Redis key
	 * @param limit 窗口上限
	 * @param expireSeconds key 过期时间（秒）
	 */
	private void checkLimit(String key, Integer limit, int expireSeconds, String errorMessage) {
		if (limit == null || limit <= 0) {
			return;
		}
		Long count = redisTemplate.opsForValue().increment(key, 1);
		if (count == null) {
			return;
		}
		if (count == 1) {
			redisTemplate.expire(key, expireSeconds, TimeUnit.SECONDS);
		}
		if (count > limit) {
			throw new JeecgBootException(errorMessage);
		}
	}
}
