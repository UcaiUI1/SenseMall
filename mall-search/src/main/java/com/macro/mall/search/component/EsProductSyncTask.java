package com.macro.mall.search.component;

import com.macro.mall.search.service.EsProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ES 商品索引增量同步定时任务：
 * 定时对比数据库与 ES 中的商品内容哈希，仅重建变化的商品向量，并清理已下架/删除的商品。
 */
@Component
public class EsProductSyncTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(EsProductSyncTask.class);

    @Autowired
    private EsProductService esProductService;

    @Value("${mall.search.sync.enabled:true}")
    private boolean syncEnabled;

    @Scheduled(
            fixedDelayString = "${mall.search.sync.interval-ms:900000}",
            initialDelayString = "${mall.search.sync.initial-delay-ms:60000}"
    )
    public void syncIncremental() {
        if (!syncEnabled) {
            return;
        }
        try {
            int count = esProductService.syncIncremental();
            if (count > 0) {
                LOGGER.info("ES 增量同步更新了 {} 条商品", count);
            }
        } catch (Exception e) {
            LOGGER.error("ES 增量同步失败", e);
        }
    }
}
