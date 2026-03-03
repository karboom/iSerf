package me.karboom.java.iSerf.util;

import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Slf4j
public class HttpUtil {

    private static OkHttpClient client;

    private HttpUtil() {
    }

    public static OkHttpClient getClient() {
        if (client == null) {
            synchronized (HttpUtil.class) {
                if (client == null) {
                    ThreadFactory threadFactory = Thread.ofVirtual().name("okhttp-dispatcher-", 0).factory();
                    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
                    Dispatcher dispatcher = new Dispatcher(executor);
                    dispatcher.setMaxRequests(10000);
                    dispatcher.setMaxRequestsPerHost(10000);

                    client = new OkHttpClient.Builder()
                            .connectTimeout(30, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .connectionPool(new ConnectionPool(10000, 5, TimeUnit.MINUTES))
                            .dispatcher(dispatcher)
                            .build();
                }
            }
        }
        return client;
    }
}
