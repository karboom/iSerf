package me.karboom.java.iSerf.agent.tool;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.*;
import lombok.SneakyThrows;
import me.karboom.java.iSerf.util.CodeUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import me.karboom.java.iSerf.util.YAMLUtil;
import tools.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Loader {
    public Integer timeout;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public Loader(Integer timeout) {
        this.timeout = timeout != null ? timeout : 30;
    }

    /**
     * 遍历目录解析yaml或者json文件为Tool工具类
     * - 如果input.type==FUNCTION，需要从input.class字段解析出类名，加载之后赋值tool.function字段
     * @param directory 扫描的目录
     * @param targetTool 文件名称（不含后缀）不匹配的跳过
     * @return 工具列表
     */
    @SneakyThrows
    public List<Tool<?>> fromToolDir(Path directory, String targetTool) {
        var tools = new ArrayList<Tool<?>>();

        if (!Files.exists(directory)) {
            return tools;
        }

        try (var dirStream = Files.list(directory)) {
            var files = dirStream.filter(Files::isRegularFile).toList();

            for (var file : files) {
                var fileName = file.getFileName().toString();
                var extension = "";
                var dotIndex = fileName.lastIndexOf('.');
                if (dotIndex > 0) {
                    extension = fileName.substring(dotIndex + 1).toLowerCase();
                }

                if (!"yaml".equals(extension) && !"yml".equals(extension) && !"json".equals(extension)) {
                    continue;
                }

                var baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
                if (targetTool != null && !targetTool.isEmpty() && !targetTool.equals(baseName)) {
                    continue;
                }

                var content = Files.readString(file);
                ObjectNode node;
                if ("json".equals(extension)) {
                    node = JSONUtil.parse(content);
                } else {
                    node = YAMLUtil.parse(content.getBytes());
                }

                var nameNode = node.path("name");
                if (nameNode.isMissingNode() || nameNode.isNull()) {
                    continue;
                }
                var name = nameNode.asText(baseName);

                var descriptionNode = node.path("description");
                var description = descriptionNode.isMissingNode() || descriptionNode.isNull() ? null : descriptionNode.asText();

                var typeNode = node.path("type");
                var type = typeNode.isMissingNode() || typeNode.isNull() ? null : typeNode.asText();

                var classNode = node.path("class");
                var className = classNode.isMissingNode() || classNode.isNull() ? null : classNode.asText();

                var parameters = new ArrayList<Tool.Parameter>();
                var paramsNode = node.path("parameters");
                if (!paramsNode.isMissingNode() && !paramsNode.isNull() && paramsNode.isArray()) {
                    paramsNode.forEach(paramNode -> {
                        var paramName = paramNode.path("name").isMissingNode() ? null : paramNode.path("name").asText();
                        if (paramName == null) {
                            return;
                        }
                        var paramType = paramNode.path("type").isMissingNode() ? "string" : paramNode.path("type").asText();
                        var paramDescNode = paramNode.path("description");
                        var paramDesc = paramDescNode.isMissingNode() || paramDescNode.isNull() ? null : paramDescNode.asText();
                        var requiredNode = paramNode.path("required");
                        var required = !requiredNode.isMissingNode() && requiredNode.asBoolean(false);

                        var itemsNode = paramNode.path("items");
                        List<Tool.Parameter> properties = null;
                        if (!itemsNode.isMissingNode() && !itemsNode.isNull() && itemsNode.has("type")) {
                            var itemType = itemsNode.path("type").asText("string");
                            properties = new ArrayList<>();
                            properties.add(new Tool.Parameter("item", itemType, null, false, null));
                        }

                        parameters.add(new Tool.Parameter(paramName, paramType, paramDesc, required, properties));
                    });
                }

                FunctionWrapper<Object> function = null;
                Class<?> parameterClass = null;
                if (Tool.TYPE.FUNCTION.equals(type) && className != null) {
                    var clazz = Class.forName(className);
                    var instance = clazz.getDeclaredConstructor().newInstance();
                    function = (FunctionWrapper<Object>) instance;

                    // 通过反射获取工具类的 description 静态字段
                    try {
                        var descriptionField = instance.getClass().getDeclaredField("description");
                        var reflectedDesc = (String) descriptionField.get(null);
                        if (reflectedDesc != null) {
                            description = reflectedDesc;
                        }
                    } catch (Exception e) {
                        // description 字段可选，没有则使用 YAML/JSON 中的值
                    }

                    // 通过反射从 toolInstance 获取 Parameter 静态内部类
                    try {
                        for (var declaredClass : instance.getClass().getDeclaredClasses()) {
                            if ("Parameter".equals(declaredClass.getSimpleName())) {
                                parameterClass = declaredClass;
                                break;
                            }
                        }
                    } catch (Exception e) {
                        // Parameter 类可选
                    }
                }

                var toolBuilder = Tool.<Object>builder()
                        .name(name)
                        .type(type != null ? type : Tool.TYPE.FUNCTION)
                        .function(function)
                        .paramType((Class<Object>) parameterClass)
                        .parameters(parameters);

                if (description != null) {
                    toolBuilder.description(description);
                }

                tools.add(toolBuilder.build());
            }
        }

        return tools;
    }
    /**
     * 遍历目录，所有的一级子文件夹为工具名称，二级子文件夹为工具的版本
     * 工具文件夹下的 info.json 记录了当前引用的版本
     * 每个版本下的静态类 Parameter 记录了参数内容，静态变量 description 记录了工具的说明，需要调用 CodeUtil 编译后获取
     * @param targetTool  如果非 null，只筛选对应的一级子文件夹
     * @param targetVersion 如果非 null，直接访问对应的版本
     * @return
     */
    @SneakyThrows
    public List<Tool<?>> fromIFunction(String directory, String targetTool, String targetVersion){
        var tools = new ArrayList<Tool<?>>();
        var iFunctionDir = Path.of(directory);
        
        if (!Files.exists(iFunctionDir)) {
            return tools;
        }
        
        // 遍历一级子文件夹（工具名称）
        try (var dirStream = Files.list(iFunctionDir)) {
            var toolDirs = dirStream.filter(Files::isDirectory).toList();
            
            for (var toolDir : toolDirs) {
                var toolName = toolDir.getFileName().toString();
                
                // 如果指定了目标工具且当前工具不是目标工具，则跳过
                if (targetTool != null && !targetTool.isEmpty() && !targetTool.equals(toolName)) {
                    continue;
                }
                
                // 读取工具目录下的 info.json 获取当前版本
                var toolInfoPath = toolDir.resolve("info.json");
                var currentVersion = "fallback"; // 默认值
                
                if (Files.exists(toolInfoPath)) {
                    var toolInfoContent = Files.readString(toolInfoPath);
                    var toolInfo = JSONUtil.parse(toolInfoContent);
                    
                    if (toolInfo.has("current")) {
                        currentVersion = toolInfo.get("current").asText();
                    }
                }
                
                // 如果指定了目标版本，则使用目标版本，否则使用当前版本
                var versionToUse = targetVersion != null && !targetVersion.isEmpty() ? targetVersion : currentVersion;
                var versionDir = toolDir.resolve(versionToUse);
                
                if (!Files.exists(versionDir) || !Files.isDirectory(versionDir)) {
                    continue;
                }
                
                // 读取工具类源码
                var toolClassName = StrUtil.upperFirst(toolName);
                var toolClassFile = versionDir.resolve("%s.java".formatted(toolClassName));
                
                if (!Files.exists(toolClassFile)) {
                    continue;
                }
                
                var toolClassCode = Files.readString(toolClassFile);
                
                // 创建临时编译目录
                var compileDir = Path.of(System.getProperty("java.io.tmpdir"), "iSerf", "compiled", toolName, versionToUse);
                Files.createDirectories(compileDir);
                
                // 编译工具类
                CodeUtil.compile(toolClassCode, compileDir.toString());
                
                // 加载工具类
                var fullClassName = "%s.%s.%s".formatted(toolName, versionToUse, toolClassName);
                var toolInstance = CodeUtil.load(compileDir.toString(), toolClassName);
                CodeUtil.load(compileDir.toString(), "%s$Parameter".formatted(toolClassName));
                
                if (toolInstance == null || toolInstance instanceof Integer) {
                    continue;
                }
                
                // 通过反射获取工具类的 description 静态字段
                String description = null;
                try {
                    var descriptionField = toolInstance.getClass().getDeclaredField("description");
                    description = (String) descriptionField.get(null);
                } catch (Exception e) {
                    // description 字段可选，没有则使用 null
                }
                
                // 通过反射从 toolInstance 获取 Parameter 静态内部类
                Class<?> parameterClass = null;
                try {
                    for (var declaredClass : toolInstance.getClass().getDeclaredClasses()) {
                        if ("Parameter".equals(declaredClass.getSimpleName())) {
                            parameterClass = (Class<?>) declaredClass;
                            break;
                        }
                    }
                } catch (Exception e) {
                    // Parameter 类可选
                }
                
                // 创建工具对象
                var toolBuilder = Tool.<Object>builder()
                    .name(toolName)
                    .description(description)
                    .type(Tool.TYPE.IFUNCTION)
                        .iDirectory(directory);
//                    .iDirectory("%s/%s".formatted(directory, toolName));
                
                if (description != null) {
                    toolBuilder.description(description);
                }
                
                if (parameterClass != null) {
                    toolBuilder.paramType((Class<Object>) parameterClass);
                }
                
                var tool = toolBuilder.build();
                
                tools.add(tool);
            }
        }
        
        return tools;
    }

    /**
     * 通过命令行解析所有 MCP 工具
     *
     * @param command MCP 服务命令
     * @param args    命令行参数
     * @return 工具列表
     */
    public List<Tool> fromMCPCli(String command, HashMap<String, String> args) {
        List<Tool> tools = new ArrayList<Tool>();
        
        try {
            // 构建命令行
            var commandList = new ArrayList<String>();
            commandList.add(command);
            
            if (args != null) {
                for (Map.Entry<String, String> entry : args.entrySet()) {
                    commandList.add(entry.getKey());
                    commandList.add(entry.getValue());
                }
            }
            
            // 启动进程
            var processBuilder = new ProcessBuilder(commandList);
            processBuilder.redirectErrorStream(true);
            var process = processBuilder.start();
            
            // 发送 list_tools 请求
            try (var os = process.getOutputStream()) {
                var request = createMCPRequest("tools/list", new HashMap<>());
                os.write(request.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            
            // 读取响应
            var response = new StringBuilder();
            try (var reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                var line = "";
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            
            // 等待进程结束
            var finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("MCP CLI timeout after " + timeout + " seconds");
            }
            
            // 解析响应
            tools = parseMCPToolsResponse(response.toString(), "mcp-cli", command, args);
            
        } catch (Exception e) {
            System.err.println("Error loading MCP tools from CLI: " + e.getMessage());
            e.printStackTrace();
        }
        
        return tools;
    }

    /**
     * 通过 HTTP 接口解析所有 MCP 工具
     *
     * @param url     MCP 服务 URL
     * @param headers HTTP 请求头
     * @return 工具列表
     */
    public List<Tool> fromMCPHttp(String url, HashMap<String, Object> headers) {
        List<Tool> tools = new ArrayList<>();
        
        try {
            // 创建 WebClient
            var webClientBuilder = WebClient.builder()
                    .baseUrl(url)
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024));
            
            // 添加自定义请求头
            if (headers != null) {
                for (var entry : headers.entrySet()) {
                    webClientBuilder.defaultHeader(entry.getKey(), entry.getValue().toString());
                }
            }


            // 创建 WebClient 传输层
            var transport = WebClientStreamableHttpTransport.builder(webClientBuilder).build();
            
            // 创建 MCP 客户端 (同步 API)
            try (var client = McpClient.sync(transport).build()) {
                // 初始化连接
                client.initialize();
                
                // 发送 list_tools 请求
                var response = client.listTools();
                
                // 转换工具列表
                if (response.tools() != null) {
                    for (var mcpTool : response.tools()) {
                        var builder = Tool.<Map>builder();
                        
                        // 设置基本信息
                        builder.name(mcpTool.name());
                        builder.description(mcpTool.description());
                        builder.type("mcp-http");
                        builder.url(url);
                        
                        // 转换 headers 为 String 类型
                        var httpHeaders = new HashMap<String, String>();
                        if (headers != null) {
                            for (var entry : headers.entrySet()) {
                                httpHeaders.put(entry.getKey(), entry.getValue().toString());
                            }
                        }
                        builder.headers(httpHeaders);
                        
                        // 解析参数
                        var parameters = new ArrayList<Tool.Parameter>();
                        if (mcpTool.inputSchema() != null) {
                            var inputSchema = mcpTool.inputSchema();
                            var properties = inputSchema.properties();
                            var requiredFields = inputSchema.required();
                            
                            if (properties != null) {
                                for (var entry : properties.entrySet()) {
                                    var paramName = entry.getKey();
                                    var paramValue = entry.getValue();
                                    
                                    // 处理 Object 类型的属性值，可能是 Map 或其他结构
                                    var paramType = "string";
                                    var paramDesc = "";
                                    
                                    if (paramValue instanceof Map<?, ?> paramMap) {
                                        // 如果是 Map，尝试提取 type 和 description
                                        if (paramMap.get("type") != null) {
                                            paramType = paramMap.get("type").toString();
                                        }
                                        if (paramMap.get("description") != null) {
                                            paramDesc = paramMap.get("description").toString();
                                        }
                                    }
                                    
                                    var isRequired = requiredFields != null &&
                                        requiredFields.contains(paramName);
                                    
                                    parameters.add(new Tool.Parameter(
                                        paramName,
                                        paramType,
                                        paramDesc,
                                        isRequired,
                                        null
                                    ));
                                }
                            }
                        }
                        
                        builder.parameters(parameters);
                        tools.add(builder.build());
                    }
                }
            }
            
        } catch (Exception e) {
            System.err.println("Error loading MCP tools from HTTP: " + e.getMessage());
            e.printStackTrace();
        }
        
        return tools;
    }

    /**
     * 创建 MCP 请求 JSON
     *
     * @param method MCP 方法名
     * @param params 参数
     * @return JSON 字符串
     */
    private String createMCPRequest(String method, HashMap<String, Object> params) throws Exception {
        var request = new HashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        request.put("params", params);
        return objectMapper.writeValueAsString(request);
    }

    /**
     * 解析 MCP tools/list 响应
     *
     * @param responseJson 响应 JSON 字符串
     * @param type         工具类型 (mcp-cli 或 mcp-http)
     * @param endpoint     端点 (命令或 URL)
     * @param config       配置 (args 或 headers)
     * @return 工具列表
     */
    private List<Tool> parseMCPToolsResponse(String responseJson, String type, String endpoint, HashMap<String, String> config) {
        var tools = new ArrayList<Tool>();
        
        try {
            var root = objectMapper.readTree(responseJson);
            
            // 检查是否有错误
            if (root.has("error")) {
                throw new RuntimeException("MCP error: " + root.get("error").toString());
            }
            
            // 获取 result.tools 数组
            if (root.has("result") && root.get("result").has("tools")) {
                var toolsArray = root.get("result").get("tools");
                
                for (var toolNode : toolsArray) {
                    var builder = Tool.<Map>builder();
                    
                    // 设置基本信息
                    if (toolNode.has("name")) {
                        builder.name(toolNode.get("name").asText());
                    }
                    
                    if (toolNode.has("description")) {
                        builder.description(toolNode.get("description").asText());
                    }
                    
                    // 解析参数
                    var parameters = new ArrayList<Tool.Parameter>();
                    if (toolNode.has("inputSchema")) {
                        var inputSchema = toolNode.get("inputSchema");
                        
                        if (inputSchema.has("properties")) {
                            var properties = inputSchema.get("properties");
                            var requiredFields = new ArrayList<String>();
                            
                            // 获取必需字段列表
                            if (inputSchema.has("required")) {
                                var required = inputSchema.get("required");
                                required.forEach(field -> requiredFields.add(field.asText()));
                            }
                            
                            // 遍历属性
                            properties.fields().forEachRemaining(entry -> {
                                var paramName = entry.getKey();
                                var paramNode = entry.getValue();
                                
                                var paramType = paramNode.has("type") ? 
                                    paramNode.get("type").asText() : "string";
                                var paramDesc = paramNode.has("description") ? 
                                    paramNode.get("description").asText() : "";
                                var isRequired = requiredFields.contains(paramName);
                                
                                parameters.add(new Tool.Parameter(
                                    paramName,
                                    paramType,
                                    paramDesc,
                                    isRequired,
                                    null
                                ));
                            });
                        }
                    }
                    
                    builder.parameters(parameters);
                    builder.type(type);
                    
                    // 设置特定类型的配置
                    if ("mcp-cli".equals(type)) {
                        builder.command(endpoint);
                        builder.environment(config);
                    } else if ("mcp-http".equals(type)) {
                        builder.url(endpoint);
                        builder.headers(config);
                    }
                    
                    tools.add(builder.build());
                }
            }
            
        } catch (Exception e) {
            System.err.println("Error parsing MCP response: " + e.getMessage());
            e.printStackTrace();
        }
        
        return tools;
    }

}