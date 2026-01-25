package me.karboom.java.iSlogger.kit.tool;

import me.karboom.java.iSlogger.tool.Tool;
import me.karboom.java.iSlogger.tool.FunctionWrapper;

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
        return Tool.builder()
                .name("read_file")
                .description("从文件中读取内容")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("path", "string", "要读取的文件路径", true)
                ))
                .function((params) -> {
                    var filePath = Path.of((String) params.get("path"));
                    var fullPath = Path.of(root).resolve(filePath).normalize();
                    
                    // 确保路径在root目录下
                    if (!fullPath.startsWith(Path.of(root))) {
                        throw new SecurityException("访问被拒绝：路径在根目录之外");
                    }
                    
                    try {
                        return Files.readString(fullPath);
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
        return Tool.builder()
                .name("write_file")
                .description("向文件写入内容")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("path", "string", "要写入的文件路径", true),
                        new Tool.Parameter("content", "string", "要写入文件的内容", true)
                ))
                .function((params) -> {
                    var filePath = Path.of((String) params.get("path"));
                    var fullPath = Path.of(root).resolve(filePath).normalize();
                    
                    // 确保路径在root目录下
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
                        return "文件写入成功";
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
        return Tool.builder()
                .name("list_directory")
                .description("列出目录内容")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("path", "string", "要列出的目录路径", false)
                ))
                .function((params) -> {
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
                        return Arrays.toString(files);
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
