package me.karboom.java.iSerf.kit.tool;

import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Tool;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class FileSystem {
    /**
     * 限定的目录范围
     */
    public String root;

    public FileSystem(String root) {
        this.root = root;
    }

    /**
     * 读取文件工具
     * @return
     */
    public Tool read() {
        return Tool.<Map>builder()
                .name("read_file")
                .description("从文件中读取内容")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("path", "string", "要读取的文件路径", true, null)
                ))
                .function((ctx, params) -> {
                    var filePath = Path.of((String) params.get("path"));
                    var fullPath = Path.of(root).resolve(filePath).normalize();
                    
                    // 确保路径在root目录下
                    if (!fullPath.startsWith(Path.of(root))) {
                        throw new SecurityException("访问被拒绝：路径在根目录之外");
                    }
                    
                    try {
                        var content = Files.readString(fullPath);
                        return CallResult.builder()
                                .llm(content)
                                .build();
                    } catch (IOException e) {
                        throw new RuntimeException("读取文件失败：" + e.getMessage());
                    }
                })
                .build();
    }

    /**
     * 写入文件工具
     * @return
     */
    public Tool write() {
        return Tool.<Map>builder()
                .name("write_file")
                .description("向文件写入内容")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("path", "string", "要写入的文件路径", true, null),
                        new Tool.Parameter("content", "string", "要写入文件的内容", true, null)
                ))
                .function((ctx, params) -> {
                    var filePath = Path.of((String) params.get("path"));
                    var fullPath = Path.of(root).resolve(filePath).normalize();
                    
                    // 确保路径在 root 目录下
                    if (!fullPath.startsWith(Path.of(root))) {
                        throw new SecurityException("访问被拒绝：路径在根目录之外");
                    }
                    
                    // 创建父目录
                    try {
                        Files.createDirectories(fullPath.getParent());
                    } catch (IOException e) {
                        throw new RuntimeException("创建目录失败：" + e.getMessage());
                    }
                    
                    try {
                        Files.writeString(fullPath, (String) params.get("content"));
                        return CallResult.builder()
                                .llm("文件写入成功")
                                .build();
                    } catch (IOException e) {
                        throw new RuntimeException("写入文件失败：" + e.getMessage());
                    }
                })
                .build();
    }

    /**
     * 列出目录内容工具
     * @return
     */
    public Tool list() {
        return Tool.<Map>builder()
                .name("list_directory")
                .description("列出目录内容")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("path", "string", "要列出的目录路径", false, null)
                ))
                .function((ctx, params) -> {
                    var dirPath = (String) params.get("path");
                    if (dirPath == null || dirPath.isEmpty()) {
                        dirPath = ".";
                    }
                    
                    var dir = Path.of(dirPath);
                    var fullPath = Path.of(root).resolve(dir).normalize();
                    
                    // 确保路径在root目录下
                    if (!fullPath.startsWith(Path.of(root))) {
                        throw new SecurityException("访问被拒绝：路径在根目录之外");
                    }
                    
                    try {
                        var files = Files.list(fullPath)
                                .map(path -> path.getFileName().toString())
                                .toArray(String[]::new);
                        return CallResult.builder()
                                .llm(Arrays.toString(files))
                                .build();
                    } catch (IOException e) {
                        throw new RuntimeException("列出目录失败：" + e.getMessage());
                    }
                })
                .build();
    }

    public List<Tool> all() {
        return List.of(read(), write(), list());
    }
}
