package me.karboom.java.iSerf.tool;

import me.karboom.java.iSerf.agent.tool.Loader;
import me.karboom.java.iSerf.agent.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

public class LoaderTest {
    private Loader loader;

    @BeforeEach
    void setUp() {
        loader = new Loader(5); // 5秒超时
    }

    @Test
    void testFromMCPHttp() {
        var valid = "http://localhost:3000";
        var tools6 = loader.fromMCPHttp(valid, null);
        assertTrue(!tools6.isEmpty(), "应该能处理有效的URL");
    }

    @Test
    void testFromMCPCli() {
        var command = "echo";
        var args = new HashMap<String, String>();
        args.put("test", "value");
        
        var tools = loader.fromMCPCli(command, args);
        assertNotNull(tools, "应该返回非空列表");
        assertTrue(tools.isEmpty(), "对于非MCP命令应该返回空列表");
    }

    @Test
    void testFromIFunction() {
        var tools = loader.fromIFunction("src/main/java/me/karboom/java/iSlogger/iFunction", null, null);
        assertNotNull(tools, "应该返回非空列表");
        assertTrue(tools.size() > 0, "应该找到至少一个iFunction工具");
        
        var echartsTool = tools.stream()
            .filter(tool -> "echarts".equals(tool.getName()))
            .findFirst();
        assertTrue(echartsTool.isPresent(), "应该找到echarts工具");
        
        var tool = echartsTool.get();
        assertEquals("echarts", tool.getName(), "工具名称应该是'echarts'");
        assertEquals(Tool.TYPE.IFUNCTION, tool.getType(), "工具类型应该是IFUNCTION");
        assertTrue(tool.getIDirectory().contains("echarts/fallback"), "工具目录应该指向fallback版本");
    }
}
