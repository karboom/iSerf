package me.karboom.java.iSerf.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.persistence.IPersistence;
import me.karboom.java.iSerf.agent.persistence.NfsPersistence;
import me.karboom.java.iSerf.billing.ILedger;
import me.karboom.java.iSerf.billing.NfsLedger;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;

/**
 * 配置单例类，启动时从 classpath 下的 iSerf.yml 读取配置，
 * 若文件不存在则使用默认值，已存在的字段会合并覆盖。
 */
@Slf4j
@Data
public class Config {

    public ILedger defaultLedger;
    public IPersistence defaultPersistence;
    public HttpConfig http = new HttpConfig();

    private static final Config INSTANCE = new Config();

    public static Config getInstance() {
        return INSTANCE;
    }

    private Config() {
        var baseDir = Path.of(System.getProperty("user.home"), ".iserf");
        this.defaultLedger = new NfsLedger(baseDir.resolve("ledger").toString(), NfsLedger.Format.JSONL);
        this.defaultPersistence = new NfsPersistence(baseDir.resolve("agent").toString());
        loadFromYaml();
    }

    private void loadFromYaml() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("iSerf.yml")) {
            if (in == null) {
                log.info("iSerf.yml not found on classpath, using default config");
                return;
            }
            var mapper = new ObjectMapper(new YAMLFactory());
            var root = mapper.readTree(in);
            var iSerfNode = root.get("iSerf");
            if (iSerfNode != null) {
                var httpNode = iSerfNode.get("http");
                if (httpNode != null) {
                    var externalHttp = mapper.treeToValue(httpNode, HttpConfig.class);
                    if (externalHttp != null) {
                        merge(this.http, externalHttp);
                    }
                }
                log.info("Config loaded from iSerf.yml");
            }
        } catch (Exception e) {
            log.warn("Failed to load iSerf.yml: {}, using default config", e.getMessage());
        }
    }

    /**
     * 通用字段级合并：将 source 中非 null 的字段值覆盖到 target，
     * 未显式指定的字段保留 target 原有值。嵌套配置对象会递归合并。
     */
    private static void merge(Object target, Object source) {
        for (Field field : target.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                var value = field.get(source);
                if (value == null) continue;

                var existing = field.get(target);
                // 若双方均为非基本类型对象，递归合并
                if (existing != null && isMergeable(field.getType())) {
                    merge(existing, value);
                } else {
                    field.set(target, value);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 判断类型是否需要递归合并（排除基本类型、包装类、String 等） */
    private static boolean isMergeable(Class<?> type) {
        if (type.isPrimitive()) return false;
        if (type == String.class) return false;
        if (Number.class.isAssignableFrom(type)) return false;
        if (Boolean.class == type) return false;
        if (type.isEnum()) return false;
        return true;
    }

    /**
     * HTTP 客户端相关配置
     */
    @Data
    public static class HttpConfig {
        /** 连接超时（秒），默认 30 */
        private Integer connectTimeout = 300;
        /** 读取超时（秒），默认 30 */
        private Integer readTimeout = 300;
        /** 写入超时（秒），默认 30 */
        private Integer writeTimeout = 300;

        private Integer callTimeout = 300;

        /** 最大并发请求数，默认 10000 */
        private Integer maxRequests = 10000;
        /** 每个 Host 最大并发请求数，默认 10000 */
        private Integer maxRequestsPerHost = 10000;
        /** 连接池最大空闲连接数，默认 10000 */
        private Integer maxIdleConnections = 10000;
        /** 连接保活时间（分钟），默认 5 */
        private Integer keepAliveMinutes = 5;
        /** 连接失败是否重试，默认 true */
        private Boolean retryOnConnectionFailure = true;
    }
}
