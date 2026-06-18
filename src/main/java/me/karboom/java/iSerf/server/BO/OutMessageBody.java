package me.karboom.java.iSerf.server.BO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.node.ObjectNode;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutMessageBody {
    public String error;

    public ObjectNode data;
}
