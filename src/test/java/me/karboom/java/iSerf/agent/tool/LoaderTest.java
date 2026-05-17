package me.karboom.java.iSerf.agent.tool;

import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.Tool;
import me.karboom.java.iSerf.agent.tool.WeatherTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

public class LoaderTest {
    private Loader loader;

    @BeforeEach
    void setUp() {
        loader = new Loader(5);
    }

    @Test
    void testFromToolDir() {
        var toolDir = Path.of("src/test/resources/agent/tool");
        var tools = loader.fromToolDir(toolDir, null);

        assertNotNull(tools, "应该返回非空列表");
        assertEquals(2, tools.size(), "应该找到2个工具");

        var weatherTool = tools.stream()
                .filter(tool -> "weather".equals(tool.getName()))
                .findFirst();
        assertTrue(weatherTool.isPresent(), "应该找到weather工具");

        var tool = weatherTool.get();
        assertEquals("weather", tool.getName());
        assertEquals("天气查询工具", tool.getDescription());
        assertEquals(Tool.TYPE.FUNCTION, tool.getType());
        assertNotNull(tool.getFunction(), "FUNCTION类型工具的function字段不应为空");

        var params = new WeatherTool.Parameter();
        params.setCity("Beijing");
        params.setDays(7);
        var ctx = Context.builder().build();
        var result = ((FunctionWrapper<Object>) tool.getFunction()).run(ctx, (Object) params);
        assertNotNull(result, "调用结果不应为空");
        assertNotNull(result.getLlm(), "调用结果的llm字段不应为空");
        assertEquals("Weather for Beijing: 7 days", result.getLlm());
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
        var tools = loader.fromIFunction("src/main/java/me/karboom/java/iSerf/iFunction", null, null);
        assertNotNull(tools, "应该返回非空列表");
        assertTrue(tools.size() > 0, "应该找到至少一个iFunction工具");
        
        var echartsTool = tools.stream()
            .filter(tool -> "echarts".equals(tool.getName()))
            .findFirst();
        assertTrue(echartsTool.isPresent(), "应该找到echarts工具");
        
        var tool = echartsTool.get();
        assertEquals("echarts", tool.getName(), "工具名称应该是'echarts'");
        assertEquals(Tool.TYPE.IFUNCTION, tool.getType(), "工具类型应该是IFUNCTION");
    }
}
