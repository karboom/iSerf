package me.karboom.java.iSerf.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Config")
class ConfigTest {

    @Nested
    @DisplayName("默认配置")
    class Defaults {

        @Test
        @DisplayName("HttpConfig 所有字段具有预期默认值")
        void httpConfigDefaults() {
            var http = Config.getInstance().getHttp();

            assertEquals(30, http.getConnectTimeout());
            assertEquals(30, http.getReadTimeout());
            assertEquals(30, http.getWriteTimeout());
            assertEquals(10000, http.getMaxRequests());
            assertEquals(10000, http.getMaxRequestsPerHost());
            assertEquals(10000, http.getMaxIdleConnections());
            assertEquals(5, http.getKeepAliveMinutes());
            assertTrue(http.getRetryOnConnectionFailure());
        }

        @Test
        @DisplayName("defaultLedger 已初始化")
        void defaultLedgerInitialized() {
            assertNotNull(Config.getInstance().getDefaultLedger());
        }
    }

    @Nested
    @DisplayName("merge 通用合并")
    class Merge {

        @Test
        @DisplayName("部分覆写：未指定字段保留默认值")
        void partialOverride() throws Exception {
            var defaults = new Config.HttpConfig();
            var overrides = new Config.HttpConfig();
            overrides.setConnectTimeout(60);
            overrides.setMaxRequests(500);
            // 其余字段保持 null，不指定

            invokeMerge(defaults, overrides);

            // 指定的字段被覆盖
            assertEquals(60, defaults.getConnectTimeout());
            assertEquals(500, defaults.getMaxRequests());
            // 未指定的字段保留默认值
            assertEquals(30, defaults.getReadTimeout());
            assertEquals(30, defaults.getWriteTimeout());
            assertEquals(10000, defaults.getMaxRequestsPerHost());
            assertEquals(10000, defaults.getMaxIdleConnections());
            assertEquals(5, defaults.getKeepAliveMinutes());
            assertTrue(defaults.getRetryOnConnectionFailure());
        }

        @Test
        @DisplayName("全部覆写")
        void fullOverride() throws Exception {
            var defaults = new Config.HttpConfig();
            var overrides = new Config.HttpConfig();
            overrides.setConnectTimeout(10);
            overrides.setReadTimeout(20);
            overrides.setWriteTimeout(30);
            overrides.setMaxRequests(100);
            overrides.setMaxRequestsPerHost(50);
            overrides.setMaxIdleConnections(200);
            overrides.setKeepAliveMinutes(10);
            overrides.setRetryOnConnectionFailure(false);

            invokeMerge(defaults, overrides);

            assertEquals(10, defaults.getConnectTimeout());
            assertEquals(20, defaults.getReadTimeout());
            assertEquals(30, defaults.getWriteTimeout());
            assertEquals(100, defaults.getMaxRequests());
            assertEquals(50, defaults.getMaxRequestsPerHost());
            assertEquals(200, defaults.getMaxIdleConnections());
            assertEquals(10, defaults.getKeepAliveMinutes());
            assertFalse(defaults.getRetryOnConnectionFailure());
        }

        @Test
        @DisplayName("null 字段不覆盖已有值")
        void nullFieldsDontOverride() throws Exception {
            var defaults = new Config.HttpConfig();
            var overrides = new Config.HttpConfig();
            overrides.setConnectTimeout(null);
            overrides.setReadTimeout(null);

            invokeMerge(defaults, overrides);

            assertEquals(30, defaults.getConnectTimeout()); // 保留默认
            assertEquals(30, defaults.getReadTimeout());     // 保留默认
        }
    }

    @Nested
    @DisplayName("单例")
    class Singleton {

        @Test
        @DisplayName("getInstance 始终返回同一实例")
        void sameInstance() {
            var a = Config.getInstance();
            var b = Config.getInstance();
            assertSame(a, b);
        }
    }

    /** 通过反射调用私有 merge 方法 */
    private static void invokeMerge(Object target, Object source) throws Exception {
        Method merge = Config.class.getDeclaredMethod("merge", Object.class, Object.class);
        merge.setAccessible(true);
        merge.invoke(null, target, source);
    }
}
