package me.karboom.java.iSerf.agent.tool;

import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentMessage;
import org.junit.jupiter.api.*;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolHandler MCP 调用测试
 * 验证 MCP HTTP 工具的完整调用流程
 */
public class ToolHandlerMCPTest {

    private static DisposableServer mcpHttpServer;
    private static McpSyncServer mcpServer;
    private static int mcpPort;
    private static ToolHandler toolHandler;

    @BeforeAll
    static void setUp() {
        // 创建 WebFlux transport
        var transportProvider = WebFluxStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(new com.fasterxml.jackson.databind.ObjectMapper()))
                .messageEndpoint("/mcp")
                .build();

        // 定义测试工具 1: echo
        var echoTool = new McpSchema.Tool(
                "echo",
                null,
                "Echo tool - returns the input message",
                new McpSchema.JsonSchema(
                        "object",
                        Map.of(
                                "message", Map.of("type", "string", "description", "The message to echo")
                        ),
                        List.of("message"),
                        null,
                        null,
                        null
                ),
                null,
                null,
                null
        );

        // 定义测试工具 2: add
        var addTool = new McpSchema.Tool(
                "add",
                null,
                "Add two numbers",
                new McpSchema.JsonSchema(
                        "object",
                        Map.of(
                                "a", Map.of("type", "number", "description", "First number"),
                                "b", Map.of("type", "number", "description", "Second number")
                        ),
                        List.of("a", "b"),
                        null,
                        null,
                        null
                ),
                null,
                null,
                null
        );

        // 创建 MCP Server
        mcpServer = McpServer.sync(transportProvider)
                .serverInfo("test-mcp-server", "1.0.0")
                .tool(echoTool, (exchange, args) -> {
                    var message = (String) args.get("message");
                    return new McpSchema.CallToolResult(
                            List.of(new McpSchema.TextContent("Echo: " + message)),
                            false
                    );
                })
                .tool(addTool, (exchange, args) -> {
                    var a = ((Number) args.get("a")).doubleValue();
                    var b = ((Number) args.get("b")).doubleValue();
                    return new McpSchema.CallToolResult(
                            List.of(new McpSchema.TextContent("Result: " + (a + b))),
                            false
                    );
                })
                .build();

        // 启动 Reactor Netty HTTP Server
        var routerFunction = transportProvider.getRouterFunction();
        var httpHandler = RouterFunctions.toHttpHandler(routerFunction);
        var reactorNettyHandler = new ReactorHttpHandlerAdapter(httpHandler);
        mcpHttpServer = HttpServer.create()
                .port(0)
                .handle(reactorNettyHandler)
                .bindNow();

        mcpPort = mcpHttpServer.port();

        // 加载 MCP 工具
        var loader = new Loader(10);
        var url = "http://localhost:" + mcpPort;
        var tools = loader.fromMCPHttp(url, null);

        // 创建 ToolHandler
        toolHandler = new ToolHandler(tools, 3);
    }

    @AfterAll
    static void tearDown() {
        if (mcpHttpServer != null) {
            mcpHttpServer.disposeNow();
        }
        if (mcpServer != null) {
            mcpServer.close();
        }
    }

    // region ========== MCP HTTP 工具调用测试 ==========

    @Nested
    @Timeout(30)
    class InvokeMCPHttpTests {

        @Test
        void testInvokeEchoTool() {
            // 构建工具调用
            var args = new HashMap<String, Object>();
            args.put("message", "Hello, MCP!");

            var call = AgentMessage.ToolCall.builder()
                    .id("call-1")
                    .name("echo")
                    .arguments(args)
                    .build();

            // 调用工具
            var results = toolHandler.invoke(null, List.of(call));

            // 验证结果
            assertEquals(1, results.size());
            var result = results.get(0).getResult();
            assertNotNull(result, "结果不应为空");
            assertNotNull(result.getLlm(), "LLM 结果不应为空");
            assertTrue(result.getLlm().contains("Echo: Hello, MCP!"), "结果应包含 echo 内容");
            assertNull(result.getError(), "不应有错误");
        }

        @Test
        void testInvokeAddTool() {
            // 构建工具调用
            var args = new HashMap<String, Object>();
            args.put("a", 10);
            args.put("b", 20);

            var call = AgentMessage.ToolCall.builder()
                    .id("call-2")
                    .name("add")
                    .arguments(args)
                    .build();

            // 调用工具
            var results = toolHandler.invoke(null, List.of(call));

            // 验证结果
            assertEquals(1, results.size());
            var result = results.get(0).getResult();
            assertNotNull(result, "结果不应为空");
            assertNotNull(result.getLlm(), "LLM 结果不应为空");
            assertTrue(result.getLlm().contains("Result: 30.0"), "结果应包含计算结果");
            assertNull(result.getError(), "不应有错误");
        }

        @Test
        void testInvokeMultipleTools() {
            // 构建多个工具调用
            var echoArgs = new HashMap<String, Object>();
            echoArgs.put("message", "Test message");

            var addArgs = new HashMap<String, Object>();
            addArgs.put("a", 5);
            addArgs.put("b", 15);

            var echoCall = AgentMessage.ToolCall.builder()
                    .id("call-echo")
                    .name("echo")
                    .arguments(echoArgs)
                    .build();

            var addCall = AgentMessage.ToolCall.builder()
                    .id("call-add")
                    .name("add")
                    .arguments(addArgs)
                    .build();

            // 调用工具
            var results = toolHandler.invoke(null, List.of(echoCall, addCall));

            // 验证结果
            assertEquals(2, results.size());

            var echoResult = results.get(0).getResult();
            assertNotNull(echoResult.getLlm());
            assertTrue(echoResult.getLlm().contains("Echo: Test message"));

            var addResult = results.get(1).getResult();
            assertNotNull(addResult.getLlm());
            assertTrue(addResult.getLlm().contains("Result: 20.0"));
        }

        @Test
        void testInvokeNonExistentTool() {
            // 构建不存在的工具调用
            var args = new HashMap<String, Object>();
            args.put("message", "test");

            var call = AgentMessage.ToolCall.builder()
                    .id("call-nonexistent")
                    .name("nonexistent_tool")
                    .arguments(args)
                    .build();

            // 调用工具
            var results = toolHandler.invoke(null, List.of(call));

            // 验证结果
            assertEquals(1, results.size());
            var result = results.get(0).getResult();
            assertNotNull(result, "结果不应为空");
            assertTrue(result.getLlm().contains("Tool not found"), "应返回工具未找到错误");
        }
    }

    // endregion
}
