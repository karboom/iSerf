package me.karboom.java.iSerf.rag.scene;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Document 测试类
 */
public class DocumentTest {

    private Document document;
    private String indexFilePath;

    @BeforeEach
    void setUp() {
        document = new Document();
        document.dir = "build/test/rag";
    }

    @Test
    void testBuildIndex() {
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            var pdfPath = Paths.get("src/test/resources/rag/2023-annual-report.pdf");
            
            assertTrue(java.nio.file.Files.exists(pdfPath), "PDF 文件应该存在");
            
            indexFilePath = document.buildIndex(pdfPath);
            
            assertNotNull(indexFilePath, "索引文件路径不应为空");
            assertTrue(java.nio.file.Files.exists(Paths.get(indexFilePath)), "索引文件应该被创建");
            
            System.out.println("索引文件路径：" + indexFilePath);
        });
    }

    @Test
    void testSearch() {
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            var indexFilePath = ("src/test/resources/rag/2023-annual-report_structure.json");
            

            var query = "What is the company's revenue in 2023?";
            var result = document.search(query, indexFilePath);
            
            assertNotNull(result, "搜索结果不应为空");
            System.out.println("搜索结果：");
            System.out.println(result);
        });
    }
}