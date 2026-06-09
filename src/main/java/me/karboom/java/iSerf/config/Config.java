package me.karboom.java.iSerf.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.billing.ILedger;
import me.karboom.java.iSerf.billing.NfsLedger;

import java.io.InputStream;

/**
 * 配置单例类，启动时从 classpath 下的 iserf.yml 读取配置，
 * 若文件不存在则使用默认值。
 */
@Slf4j
@Data
public class Config {

    public ILedger defaultLedger;
    public HttpConfig http = new HttpConfig();

    // 饿汉式单例 - 类加载时创建实例，线程安全
    private static final Config INSTANCE = new Config();

    // 全局访问点
    public static Config getInstance() {
        return INSTANCE;
    }

    private Config() {
        this.defaultLedger = new NfsLedger("/tmp/iserf.ledge", NfsLedger.Format.JSONL);
        loadFromYaml();
    }

    /**
     * 从 classpath 读取 iserf.yml 并覆盖默认配置
     */
    private void loadFromYaml() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("iserf.yml")) {
            if (in == null) {
                log.info("iserf.yml not found on classpath, using default config");
                return;
            }
            var mapper = new ObjectMapper(new YAMLFactory());
            var root = mapper.readTree(in);
            var iSerfNode = root.get("iSerf");
            if (iSerfNode != null) {
                var external = mapper.treeToValue(iSerfNode, Config.class);
                if (external != null && external.http != null) {
                    this.http = external.http;
                    log.info("HTTP config loaded from iserf.yml");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load iserf.yml: {}, using default config", e.getMessage());
        }
    }

    /**
     * HTTP 客户端相关配置
     */
    @Data
    public static class HttpConfig {
        /** 连接超时（秒），默认 30 */
        private int connectTimeout = 30;
        /** 读取超时（秒），默认 30 */
        private int readTimeout = 30;
        /** 写入超时（秒），默认 30 */
        private int writeTimeout = 30;
        /** 最大并发请求数，默认 10000 */
        private int maxRequests = 10000;
        /** 每个 Host 最大并发请求数，默认 10000 */
        private int maxRequestsPerHost = 10000;
        /** 连接池最大空闲连接数，默认 10000 */
        private int maxIdleConnections = 10000;
        /** 连接保活时间（分钟），默认 5 */
        private int keepAliveMinutes = 5;
        /** 连接失败是否重试，默认 true */
        private boolean retryOnConnectionFailure = true;
    }
}
