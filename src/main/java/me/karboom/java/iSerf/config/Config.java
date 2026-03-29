package me.karboom.java.iSerf.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.billing.ILedger;
import me.karboom.java.iSerf.billing.NfsLedger;

import java.nio.file.Path;

/**
 * 配置单例类
 */
@Slf4j
@Data
public class Config {
    public ILedger defaultLedger;

    // 饿汉式单例 - 类加载时创建实例，线程安全
    private static final Config INSTANCE = new Config();

    // 全局访问点
    public static Config getInstance() {
        return INSTANCE;
    }


    private Config() {
        // Todo 默认目录设置
        this.defaultLedger = new NfsLedger("/tmp/iserf.ledge", NfsLedger.Format.JSONL);
    }
}