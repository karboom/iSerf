package me.karboom.java.iSerf.util;

import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.config.Config;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Slf4j
public class HttpUtil {

    private static volatile OkHttpClient client;

    private HttpUtil() {
    }

    public static OkHttpClient getClient() {
        if (client == null) {
            synchronized (HttpUtil.class) {
                if (client == null) {
                    var httpConfig = Config.getInstance().getHttp();

                    ThreadFactory threadFactory = Thread.ofVirtual().name("okhttp-dispatcher-", 0).factory();
                    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
                    Dispatcher dispatcher = new Dispatcher(executor);
                    dispatcher.setMaxRequests(httpConfig.getMaxRequests());
                    dispatcher.setMaxRequestsPerHost(httpConfig.getMaxRequestsPerHost());

                    client = new OkHttpClient.Builder()
                            .connectTimeout(httpConfig.getConnectTimeout(), TimeUnit.SECONDS)
                            .readTimeout(httpConfig.getReadTimeout(), TimeUnit.SECONDS)
                            .writeTimeout(httpConfig.getWriteTimeout(), TimeUnit.SECONDS)
                            .retryOnConnectionFailure(httpConfig.getRetryOnConnectionFailure())
                            .connectionPool(new ConnectionPool(
                                    httpConfig.getMaxIdleConnections(),
                                    httpConfig.getKeepAliveMinutes(),
                                    TimeUnit.MINUTES))
                            .dispatcher(dispatcher)
                            .build();
                }
            }
        }
        return client;
    }
}
