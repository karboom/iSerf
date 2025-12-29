package me.karboom.java.iSlogger.tool;

import java.util.Map;

@FunctionalInterface
public interface FunctionWrapper {
    String run(Map<String, Object> params);
}
