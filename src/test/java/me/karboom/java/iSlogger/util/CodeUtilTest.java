package me.karboom.java.iSlogger.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CodeUtilTest {
    @Test
    public void testRun() throws Exception {
        var code = """
            import java.util.*;
            import me.karboom.java.iSlogger.tool.FunctionWrapper;
            
            public class GetTime implements FunctionWrapper {

                @Override
                public String run(Map<String, Object> params) {
                    var a = List.of(1);
                    System.out.println("Hello from dynamic code!");
                    return "1";
                }
            }
            """;
        assertDoesNotThrow(() -> CodeUtil.run(code, "/home/karboom/projects/karboom/java/iSlogger/class/time/current"));
    }
}
