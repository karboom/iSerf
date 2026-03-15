package me.karboom.java.iSerf.util;

import lombok.SneakyThrows;

import javax.tools.ToolProvider;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.regex.Pattern;

public class CodeUtil {
    @SneakyThrows
    public static void run(String code, String path) {
        var codeDirectory = new File(path);
        if (!codeDirectory.exists()) {
            codeDirectory.mkdirs();
        }

        var codeWithoutPackage = code.replaceFirst("^\\s*package\\s+[^;]+;\\s*", "");

        var classNameMatcher = Pattern.compile("class\\s+([a-zA-Z0-9_]+)").matcher(codeWithoutPackage);
        if (!classNameMatcher.find()) {
            throw new RuntimeException("Could not find class name in code");
        }
        var className = classNameMatcher.group(1);

        var sourceFile = new File(codeDirectory, "%s.java".formatted(className));
        Files.writeString(sourceFile.toPath(), codeWithoutPackage);

        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException("Java Compiler not found. Please ensure you are running on a JDK.");
        }

        var result = compiler.run(null, null, null, sourceFile.getPath());
        if (result != 0) {
            throw new RuntimeException("Compilation failed");
        }

        try (var classLoader = new URLClassLoader(new URL[]{codeDirectory.toURI().toURL()}, CodeUtil.class.getClassLoader())) {
            var clazz = classLoader.loadClass(className);
            var instance = clazz.getDeclaredConstructor().newInstance();
            var method = clazz.getDeclaredMethod("run");
            method.invoke(instance);
        }
    }

    /**
     * 将代码编译到指定目录
     * @param code
     * @param path
     */
    @SneakyThrows
    public static void compile(String code, String path) {
        var codeDirectory = new File(path);
        if (!codeDirectory.exists()) {
            codeDirectory.mkdirs();
        }

        var codeWithoutPackage = code.replaceFirst("^\\s*package\\s+[^;]+;\\s*", "");

        var classNameMatcher = Pattern.compile("class\\s+([a-zA-Z0-9_]+)").matcher(codeWithoutPackage);
        if (!classNameMatcher.find()) {
            throw new RuntimeException("Could not find class name in code");
        }
        var className = classNameMatcher.group(1);

        var sourceFile = new File(codeDirectory, "%s.java".formatted(className));
        Files.writeString(sourceFile.toPath(), codeWithoutPackage);

        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException("Java Compiler not found. Please ensure you are running on a JDK.");
        }

        var result = compiler.run(null, null, null,
                "-cp", System.getProperty("java.class.path"),
                "-processorpath", System.getProperty("java.class.path"),
                sourceFile.getPath());
        if (result != 0) {
            throw new RuntimeException("Compilation failed");
        }
    }


    /**
     * 从指定目录加载类
     * @param path
     * @param className
     * @return
     *  // Todo ClassLoader从哪里引用
     */
    @SneakyThrows
    public static Object load(String path, String className)  {
        var classLoader = new URLClassLoader(new URL[]{new File(path).toURI().toURL()}, CodeUtil.class.getClassLoader());
        
        // 加载主类
        var clazz = classLoader.loadClass(className);
        
        // 遍历目录，加载所有静态子类（文件名格式：className$NestedClass.class）
        var classFiles = new File(path).listFiles((dir, name) -> 
            name.startsWith(className + "$") && name.endsWith(".class")
        );
        if (classFiles != null) {
            for (var classFile : classFiles) {
                var nestedClassName = classFile.getName().replace(".class", "");
                classLoader.loadClass(nestedClassName);
            }
        }
        
        return clazz.getDeclaredConstructor().newInstance();
    }
}
