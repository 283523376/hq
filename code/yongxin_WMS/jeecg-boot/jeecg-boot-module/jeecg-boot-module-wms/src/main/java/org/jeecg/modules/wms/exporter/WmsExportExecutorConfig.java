package org.jeecg.modules.wms.exporter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * @Description: 导出线程池。
 *
 * <p>**刻意不用 {@code @Async}**：项目里没有任何 {@code @EnableAsync}，加它是个影响全局的开关；
 * 显式持有 {@code TaskExecutor} 并 {@code execute(...)}，行为可预测，队列满时能自己接住
 * {@code RejectedExecutionException} 给出人话提示。
 *
 * <p>并发 2：导出是 IO + CPU 混合的重活，几十万行会吃掉连接和内存，开大只会互相拖慢并挤占业务接口。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Slf4j
@Configuration
public class WmsExportExecutorConfig {

    /** 线程池 Bean 名，注入时用 {@code @Qualifier} 指定。 */
    public static final String EXPORT_EXECUTOR = "wmsExportExecutor";

    @Bean(EXPORT_EXECUTOR)
    public ThreadPoolTaskExecutor wmsExportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("wms-export-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 关机时给正在写的文件留出收尾时间，避免留下半个 xlsx
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
