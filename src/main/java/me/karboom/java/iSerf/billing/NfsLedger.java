package me.karboom.java.iSerf.billing;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.util.CBORUtil;
import me.karboom.java.iSerf.util.JSONUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Slf4j
public class NfsLedger implements ILedger {
    private final Path filePath;
    private final String format;

    public static class Format {
        public static final String CBOR = "CBOR";
        public static final String JSONL = "JSONL";
    }

    @SneakyThrows
    public NfsLedger(String filePath, String format) {
        this.filePath = Paths.get(filePath);
        this.format = format;
        Files.createDirectories(this.filePath.getParent());
    }

    @Override
    @SneakyThrows
    public void record(Cost cost) {
        byte[] data;
        if (Format.CBOR.equals(format)) {
            data = CBORUtil.toByte(cost);
        } else {
            data = (JSONUtil.stringify(cost) + "\n").getBytes(StandardCharsets.UTF_8);
        }
        Files.write(filePath, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        log.debug("record Cost: {} -> {} format: {}", cost.id, filePath, format);
    }
}
