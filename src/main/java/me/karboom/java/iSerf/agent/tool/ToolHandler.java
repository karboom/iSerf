package me.karboom.java.iSerf.agent.tool;

import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.StrUtil;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.llm.text.Output;
import me.karboom.java.iSerf.util.*;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具调用处理器
 * 负责工具调用、缓存管理、流式工具调用合并、工具自进化
 */
@Slf4j
public class ToolHandler {

    private final List<Tool<?>> tools;
    private final List<CallCache> toolCallCaches;
    private final int maxEvoRetry;

    public ToolHandler(List<Tool<?>> tools, int maxEvoRetry) {
        this.tools = tools != null ? tools : new ArrayList<>();
        this.toolCallCaches = new ArrayList<>();
        this.maxEvoRetry = maxEvoRetry;
    }

    // region ========== 访问器 ==========

    public List<Tool<?>> getTools() {
        return tools;
    }

    public List<CallCache> getCaches() {
        return toolCallCaches;
    }

    public void addCache(CallCache cache) {
        toolCallCaches.add(cache);
    }

    // endregion

    // region ========== 工具调用 ==========

    /**
     * 调用函数
     * Todo 工具串行调用，和并行调用
     *
     * @param calls 工具调用列表
     * @return 带有调用结果的工具调用列表
     */
    public List<AgentMessage.ToolCall> invoke(Agent agent, List<AgentMessage.ToolCall> calls) {

        for (var call : calls) {
            // 根据 name 匹配对应的 Tool
            Tool matchedTool = null;
            for (Tool tool : tools) {
                if (tool.getName().equals(call.name)) {
                    matchedTool = tool;
                    break;
                }
            }

            var result = CallResult.builder().build();

            if (matchedTool == null) {
                result.setLlm("Tool not found: " + call.name);
                call.result = result;
                continue;
            }

            try {
                switch (matchedTool.getType()) {
                    case Tool.TYPE.FUNCTION:
                        // 调用本地函数

                        result = matchedTool.getFunction().run(new Context(agent), JSONUtil.convert(call.arguments, matchedTool.paramType));

                        break;

                    case Tool.TYPE.IFUNCTION:
                    {
                        try {
                            var functionPath = getIFunctionPath(matchedTool);

                            var classPath = "%s/%s/%s".formatted(functionPath.get(0), functionPath.get(1), functionPath.get(2));
                            var versionDir = "%s/%s".formatted(functionPath.get(0), functionPath.get(1));

                            // 判断 versionDir下面是否有.class文件，否则先编译
                            if (!new File("%s.class".formatted(classPath)).exists()) {
                                var javaFile = new File("%s.java".formatted(classPath));
                                CodeUtil.compile(Files.readString(javaFile.toPath()), versionDir);
                            }

                            // 加载类
                            var cls = (FunctionWrapper) CodeUtil.load(versionDir, functionPath.get(2));
                            result = (cls.run(new Context(agent), JSONUtil.convert(call.arguments, matchedTool.paramType)));
                        } catch (Exception e) {
                            result.setError((RuntimeException) e);
                        }
                    }
                    break;

                    case Tool.TYPE.MCP_HTTP:
                        // 调用 HTTP 工具
                    {
                        result = invokeMcpHttp(matchedTool, call.arguments);
                    }
                    break;

                    case Tool.TYPE.MCP_CLI:
                        // 调用 CLI 工具
                    {
                        result = invokeMcpCli(matchedTool, call.arguments);
                    }
                    break;
                    default:
                        throw new RuntimeException("函数类型不存在");
                }
            } catch (Exception e) {
                result.setLlm("工具调用错误" + e.getMessage());
            }

            call.result = result;
        }

        return calls;
    }

    // endregion

    // region ========== 缓存调用 ==========

    /**
     * 直接从缓存的参数调用工具
     * 1. 找出匹配的toolCallCache
     * 2. 调用函数，获取result.direct并且返回
     *
     * Todo 跑在哪个线程池里面
     */
    @SneakyThrows
    public ObjectNode invokeByCache(Agent agent, String toolCallId) {
        log.debug("invokeToolCallCache toolCallId: " + toolCallId);

        // 根据 toolCallId 查找缓存
        var cache = toolCallCaches.stream()
                .filter(c -> c.callId.equals(toolCallId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Tool call cache not found: " + toolCallId));

        // 根据 toolName 匹配对应的 Tool
        var matchedTool = tools.stream()
                .filter(tool -> tool.getName().equals(cache.toolName))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Tool not found: " + cache.toolName));

        var result = CallResult.builder().build();

        // Todo 这里可以封装一个函数专门调用iFunction
        var functionPath = getIFunctionPath(matchedTool);

        var classPath = "%s/%s/%s".formatted(functionPath.get(0), functionPath.get(1), functionPath.get(2));
        var versionDir = "%s/%s".formatted(functionPath.get(0), functionPath.get(1));

        // 判断 versionDir 下面是否有.class 文件，否则先编译
        if (!new File("%s.class".formatted(classPath)).exists()) {
            var javaFile = new File("%s.java".formatted(classPath));
            CodeUtil.compile(Files.readString(javaFile.toPath()), versionDir);
        }

        // 加载类
        var cls = (FunctionWrapper) CodeUtil.load(versionDir, functionPath.get(2));
        result = (cls.run(new Context(agent), cache.params));

        return result.direct;
    }

    // endregion

    // region ========== 流式合并 ==========

    /**
     * 合并函数调用片段
     *
     * 1.根据首个chunk确定choice总数
     * 2.每个choice最终积累一个完整的Output.ToolCall
     * 3.将Output.Toolcall body属性解析json，转为Item.ToolCall
     *
     * @param chunks 流式响应块列表
     * @return 合并后的工具调用列表，每个choice对应一组ToolCall
     */
    public List<List<AgentMessage.ToolCall>> merge(List<Output> chunks) {
        if (chunks.isEmpty()) {
            return new ArrayList<>();
        }

        var result = new ArrayList<List<AgentMessage.ToolCall>>();

        var mergedToolCalls = new HashMap<Integer, Output.ToolCall>();

        for (var chunk : chunks) {
            var choices = chunk.getChoices();
            if (choices == null || choices.isEmpty()) {
                continue;
            }

            var toolCalls = choices.get(0).getToolCall();
            if (toolCalls == null) {
                continue;
            }

            for (var toolCall : toolCalls) {
                var index = toolCall.getIndex();
                if (index == null) {
                    continue;
                }

                mergedToolCalls.merge(index, toolCall, (existing, newCall) -> {
                    if (newCall.getId() != null && newCall.getId() != "") {
                        existing.setId(newCall.getId());
                    }
                    if (newCall.getName() != null && newCall.getName() != "") {
                        existing.setName(newCall.getName());
                    }
                    if (newCall.getArguments() != null) {
                        var existingArgs = existing.getArguments() == null ? "" : existing.getArguments();
                        existing.setArguments(existingArgs + newCall.getArguments());
                    }
                    return existing;
                });
            }
        }

        for (var entry : mergedToolCalls.entrySet()) {
            var toolCallsForChoice = new ArrayList<AgentMessage.ToolCall>();
            var outputToolCall = entry.getValue();

            var itemToolCall = AgentMessage.ToolCall.builder()
                    .id(outputToolCall.getId())
                    .name(outputToolCall.getName())
                    .arguments(JSONUtil.parse(outputToolCall.getArguments(), HashMap.class))
                    .build();

            toolCallsForChoice.add(itemToolCall);
            result.add(toolCallsForChoice);
        }

        return result;
    }

    // endregion

    // region ========== 自进化 ==========

    /**
     * 根据错误反馈更新工具内容
     * @deprecated
     */
    public Mono<Void> update(Agent agent, AgentMessage.ToolCall toolCall) {
        // 1. 根据ToolCall 匹配tool
        var matchedTool = tools.stream()
                .filter(tool -> tool.getName().equals(toolCall.getName()))
                .findFirst()
                .orElse(null);

        if (matchedTool == null) {
            return Mono.error(new RuntimeException("Tool not found: " + toolCall.getName()));
        }

        // 2. 通过API获取代码
        var apiUrl = "http://localhost:3000/query";
        var toolName = matchedTool.getName();

        // 创建请求
        var request = new Request.Builder()
                .url(apiUrl + "?name=" + toolName)
                .get()
                .build();

        // 异步执行 HTTP 请求并处理响应
        return Mono.fromCallable(() -> HttpUtil.getClient().newCall(request).execute())
                .flatMap(response -> {
                    if (!response.isSuccessful()) {
                        return Mono.error(new RuntimeException("Failed to query tool code: " + response.code()));
                    }

                    try {
                        var responseBody = response.body().string();
                        var currentCode = JSONUtil.parse(responseBody).get("tool").get("content").asText();

                        // 3. 调用LLM更新代码
                        var prompt = "请根据以下错误信息更新代码:\n\n输入参数：\n\n%s\n\n错误信息: %s\n\n当前代码:\n%s\n\n 请修改runner里面的逻辑，仅需要告诉我最终的代码，不要带markdown标记"
                                .formatted(toolCall.arguments.toString(), toolCall.getResult().toString(), currentCode);

                        var userMessage = AgentMessage.builder()
                                .role("user")
                                .text(prompt)
                                .build();

                        var messages = new ArrayList<AgentMessage>();
                        messages.add(userMessage);

                        // 调用LLM生成更新后的代码
                        return agent.getLlmProvider().get(null, null, null).send(messages, null, null)
                                .map(chunk -> {
                                    if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                                        var delta = chunk.getChoices().get(0).getText();
                                        if (delta != null) {
                                            return delta;
                                        }
                                    }
                                    return "";
                                })
                                .reduce(new StringBuilder(), (sb, content) -> sb.append(content))
                                .map(StringBuilder::toString)
                                .flatMap(updatedCode -> {
                                    // 4. 通过API上传更新后的代码
                                    var updateApiUrl = "http://localhost:3000/update";
                                    var encodedCode = Base64.getEncoder().encodeToString(updatedCode.getBytes(StandardCharsets.UTF_8));
                                    var payload = "{\"name\": \"%s\", \"content\": \"%s\"}"
                                            .formatted(toolName, encodedCode);

                                    var updateRequestBody = RequestBody.create(
                                            payload,
                                            MediaType.get("application/json; charset=utf-8")
                                    );

                                    var updateRequest = new Request.Builder()
                                            .url(updateApiUrl)
                                            .post(updateRequestBody)
                                            .build();

                                    return Mono.fromCallable(() -> HttpUtil.getClient().newCall(updateRequest).execute())
                                            .flatMap(updateResponse -> {
                                                if (!updateResponse.isSuccessful()) {
                                                    return Mono.error(new RuntimeException("Failed to upload updated code: " + updateResponse.code()));
                                                } else {
                                                    return Mono.empty();
                                                }
                                            });
                                })
                                .onErrorResume(e -> Mono.error(e));
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                })
                .then(); // 转换为Mono<Void>
    }

    @SneakyThrows
    public Mono<Void> updateWithRetry(Agent agent, AgentMessage.ToolCall toolCall, Integer attempt) {
        var matchedTool = tools.stream()
                .filter(tool -> tool.getName().equals(toolCall.getName()))
                .findFirst()
                .orElse(null);
        if (matchedTool == null) {
            throw (new RuntimeException("Tool not found: %s".formatted(toolCall.getName())));
        }

        var functionPath = getIFunctionPath(matchedTool);
        var javaFilePath = "%s/%s/%s.java".formatted(functionPath.get(0), functionPath.get(1), functionPath.get(2));
        var javaFile = new File(javaFilePath);

        if (!javaFile.exists()) {
            throw new RuntimeException("Java file not found: %s".formatted(javaFilePath));
        }

        var currentCode = Files.readString(javaFile.toPath(), StandardCharsets.UTF_8);

        var prompt = "请根据以下错误信息更新代码:\n\n输入参数：\n\n%s\n\n错误信息: %s\n\n当前代码:\n%s\n\n 请修改runner里面的逻辑，仅需要告诉我最终的代码，不要带markdown标记"
                .formatted(toolCall.arguments.toString(), toolCall.getResult().toString(), currentCode);

        var userMessage = AgentMessage.builder()
                .role(AgentMessage.ROLE.USER)
                .text(prompt)
                .build();

        // Todo 这里直接给format
        return agent.getLlmProvider().get(null, null, null).send(List.of(userMessage), null, null)
                .reduce("", (acc, chunk) -> {
                    var text = "";
                    if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                        var delta = chunk.getChoices().get(0).getText();
                        if (delta != null) {
                            text = delta;
                        }
                    }
                    return acc + text;
                })

                .flatMap(updatedCode -> {
                    // 尝试编译、加载和运行代码
                    try {
                        var timestamp = System.currentTimeMillis();
                        var targetDir = "%s/%s".formatted(functionPath.get(0), timestamp);

                        CodeUtil.compile(updatedCode, targetDir);
                        var obj = CodeUtil.load(targetDir, StrUtil.upperFirst(StrUtil.toCamelCase(toolCall.getName())));
                        if (obj instanceof FunctionWrapper wrapper) {
                            wrapper.run(new Context(agent), toolCall.arguments);
                        } else {
                            throw new RuntimeException();
                        }
                        return Mono.empty();
                    } catch (Exception e) {
                        if (attempt >= this.maxEvoRetry) {
                            throw (new RuntimeException("Max attempts reached for updating tool: %s".formatted(toolCall.getName())));
                        }
                        // 如果编译、加载或运行失败，递归调用重试
                        return updateWithRetry(agent, toolCall, attempt + 1);
                    }
                });
    }

    // endregion

    // region ========== 辅助方法 ==========

    /**
     * 调用 MCP HTTP 工具
     * 通过 HTTP 协议连接 MCP Server 并调用工具
     *
     * @param tool      MCP HTTP 工具
     * @param arguments 调用参数
     * @return 调用结果
     */
    @SneakyThrows
    private CallResult invokeMcpHttp(Tool tool, Object arguments) {
        log.debug("<invokeMcpHttp> calling MCP HTTP tool | name={},url={}", tool.getName(), tool.getUrl());

        // 构建 WebClient
        var webClientBuilder = WebClient.builder()
                .baseUrl(tool.getUrl())
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024));

        // 添加自定义请求头
        if (tool.getHeaders() != null) {
            @SuppressWarnings("unchecked")
            var headers = (HashMap<String, String>) tool.getHeaders();
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                webClientBuilder.defaultHeader(entry.getKey(), entry.getValue());
            }
        }

        // 创建传输层
        var transport = WebClientStreamableHttpTransport.builder(webClientBuilder).build();

        // 创建 MCP 客户端并调用工具
        try (var client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .build()) {
            client.initialize();

            // 转换参数
            var args = convertArguments(arguments);

            // 调用工具
            var request = new McpSchema.CallToolRequest(tool.getName(), args);
            var mcpResult = client.callTool(request);

            log.debug("<invokeMcpHttp> MCP tool call completed | name={},isError={}", tool.getName(), mcpResult.isError());

            // 提取结果内容
            var content = extractContent(mcpResult);

            if (Boolean.TRUE.equals(mcpResult.isError())) {
                return CallResult.builder()
                        .llm(content)
                        .error(new RuntimeException("MCP tool error: " + content))
                        .build();
            }

            return CallResult.builder()
                    .llm(content)
                    .build();
        } catch (Exception e) {
            log.error("<invokeMcpHttp> failed to call MCP HTTP tool | {}", ExceptionUtil.stacktraceToString(e));
            return CallResult.builder()
                    .llm("MCP HTTP tool call failed: " + e.getMessage())
                    .error(new RuntimeException(e))
                    .build();
        }
    }

    /**
     * 调用 MCP CLI 工具
     * 通过 stdio 协议连接 MCP Server 并调用工具
     *
     * @param tool      MCP CLI 工具
     * @param arguments 调用参数
     * @return 调用结果
     */
    @SneakyThrows
    private CallResult invokeMcpCli(Tool tool, Object arguments) {
        log.debug("<invokeMcpCli> calling MCP CLI tool | name={},command={}", tool.getName(), tool.getCommand());

        // 构建命令行参数
        var command = tool.getCommand();
        var args = new ArrayList<String>();

        // 解析环境参数（command 可能包含参数）
        var commandParts = command.split("\\s+");
        var mainCommand = commandParts[0];
        for (int i = 1; i < commandParts.length; i++) {
            args.add(commandParts[i]);
        }

        // 添加 environment 中的参数
        if (tool.getEnvironment() != null) {
            @SuppressWarnings("unchecked")
            var env = (HashMap<String, String>) tool.getEnvironment();
            for (Map.Entry<String, String> entry : env.entrySet()) {
                args.add(entry.getKey());
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    args.add(entry.getValue());
                }
            }
        }

        // 创建 ServerParameters
        var serverParams = ServerParameters.builder(mainCommand)
                .args(args)
                .build();

        // 创建传输层
        var transport = new StdioClientTransport(serverParams, new JacksonMcpJsonMapper(new com.fasterxml.jackson.databind.ObjectMapper()));

        // 创建 MCP 客户端并调用工具
        try (var client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .build()) {
            client.initialize();

            // 转换参数
            var callArgs = convertArguments(arguments);

            // 调用工具
            var request = new McpSchema.CallToolRequest(tool.getName(), callArgs);
            var mcpResult = client.callTool(request);

            log.debug("<invokeMcpCli> MCP tool call completed | name={},isError={}", tool.getName(), mcpResult.isError());

            // 提取结果内容
            var content = extractContent(mcpResult);

            if (Boolean.TRUE.equals(mcpResult.isError())) {
                return CallResult.builder()
                        .llm(content)
                        .error(new RuntimeException("MCP tool error: " + content))
                        .build();
            }

            return CallResult.builder()
                    .llm(content)
                    .build();
        } catch (Exception e) {
            log.error("<invokeMcpCli> failed to call MCP CLI tool | {}", ExceptionUtil.stacktraceToString(e));
            return CallResult.builder()
                    .llm("MCP CLI tool call failed: " + e.getMessage())
                    .error(new RuntimeException(e))
                    .build();
        }
    }

    /**
     * 转换调用参数为 Map<String, Object>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertArguments(Object arguments) {
        if (arguments == null) {
            return new HashMap<>();
        }
        if (arguments instanceof Map) {
            return (Map<String, Object>) arguments;
        }
        // 如果是 ObjectNode，转换为 Map
        if (arguments instanceof ObjectNode objectNode) {
            return JSONUtil.convert(objectNode, HashMap.class);
        }
        return new HashMap<>();
    }

    /**
     * 从 MCP CallToolResult 提取文本内容
     */
    private String extractContent(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "";
        }

        return result.content().stream()
                .map(content -> {
                    if (content instanceof McpSchema.TextContent textContent) {
                        return textContent.text();
                    }
                    return content.toString();
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * 构建类名路径，不带后缀名，格式为 IDirectory/驼峰toolName/从info.json解析current字段/首字母大写驼峰toolName
     *
     * @return List.of(工具目录, 版本号, 类名)
     */
    @SneakyThrows
    public List<String> getIFunctionPath(Tool tool) {
        // 获取工具的目录
        var directory = tool.getIDirectory();
        // 获取工具名的驼峰形式
        var camelCaseName = StrUtil.toCamelCase(tool.getName());

        // 构建 info.json 文件路径
        var infoFilePath = "%s/%s/info.json".formatted(directory, camelCaseName);
        var infoFile = new File(infoFilePath);


        // 读取 info.json 中的 current 字段值，默认为 "current"
        var info = JSONUtil.parse("""
                {"current":"fallback"}
                """);
        if (infoFile.exists()) {
            info = JSONUtil.parse(Files.readString(infoFile.toPath()));
        }
        var currentVersion = info.get("current").asText();

        // 获取首字母大写的驼峰工具名
        var upperFirstCamelCaseName = StrUtil.upperFirst(camelCaseName);

        // 构建最终路径
        return List.of("%s/%s".formatted(directory, camelCaseName), currentVersion, upperFirstCamelCaseName);
    }

    // endregion
}
