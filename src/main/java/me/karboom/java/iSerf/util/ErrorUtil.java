package me.karboom.java.iSerf.util;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public class ErrorUtil {

    static public class Error extends RuntimeException {
        public Error(String message) {
            super(message);
        }
    }

    static public Error make(String message) {
        return new Error(message);
    }
}
