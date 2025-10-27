package me.karboom.java.iSlogger;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class Tool {
    @Data
    @AllArgsConstructor
    static public class Parameter {
        public String name;
        public String type;
        public String description;
        public Boolean required;
    }

    public String name;
    public String description;
    public List<Parameter> parameters;
}
