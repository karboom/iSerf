package me.karboom.java.iSlogger.server;

import me.karboom.java.iSlogger.agent.Agent;
import me.karboom.java.iSlogger.llm.text.OpenAI;
import me.karboom.java.iSlogger.tool.Loader;
import me.karboom.java.iSlogger.tool.Tool;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Tmp extends Websocket {

    @Override
    Agent createAgent(ObjectNode params) {
        var prompt = """
                你是一个数据分析助手，这是目前的pg数据库结构
                
                -- public.device_types definition
                
                -- Drop table
                
                -- DROP TABLE public.device_types;
                
                CREATE TABLE public.device_types ( id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, type_name varchar(100) NOT NULL, manufacturer varchar(100) NULL, recommended_interval_days int4 DEFAULT 30 NULL, description text NULL, created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL, CONSTRAINT device_types_pkey PRIMARY KEY (id));
                COMMENT ON TABLE public.device_types IS '设备型号定义表';
                
                -- Column comments
                
                COMMENT ON COLUMN public.device_types.type_name IS '设备型号名称';
                COMMENT ON COLUMN public.device_types.recommended_interval_days IS '推荐保养间隔天数';
                
                
                -- public.technicians definition
                
                -- Drop table
                
                -- DROP TABLE public.technicians;
                
                CREATE TABLE public.technicians ( id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, "name" varchar(50) NOT NULL, employee_id varchar(20) NULL, contact_phone varchar(20) NULL, specialization varchar(100) NULL, is_active bool DEFAULT true NULL, created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL, CONSTRAINT technicians_employee_id_key UNIQUE (employee_id), CONSTRAINT technicians_pkey PRIMARY KEY (id));
                COMMENT ON TABLE public.technicians IS '维护技术人员表';
                
                
                -- public.devices definition
                
                -- Drop table
                
                -- DROP TABLE public.devices;
                
                CREATE TABLE public.devices ( id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, serial_number varchar(50) NOT NULL, type_id int4 NOT NULL, location_name varchar(100) NULL, install_date date NULL, status varchar(20) DEFAULT 'active'::character varying NULL, last_maintenance_date date NULL, created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL, CONSTRAINT devices_pkey PRIMARY KEY (id), CONSTRAINT devices_serial_number_key UNIQUE (serial_number), CONSTRAINT devices_status_check CHECK (((status)::text = ANY ((ARRAY['active'::character varying, 'maintenance'::character varying, 'inactive'::character varying, 'scrapped'::character varying])::text[]))), CONSTRAINT fk_devices_type FOREIGN KEY (type_id) REFERENCES public.device_types(id));
                CREATE INDEX idx_devices_serial ON public.devices USING btree (serial_number);
                CREATE INDEX idx_devices_status ON public.devices USING btree (status);
                COMMENT ON TABLE public.devices IS '具体设备实例表';
                
                -- Column comments
                
                COMMENT ON COLUMN public.devices.status IS '设备当前状态';
                
                
                -- public.maintenance_items definition
                
                -- Drop table
                
                -- DROP TABLE public.maintenance_items;
                
                CREATE TABLE public.maintenance_items ( id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, item_name varchar(100) NOT NULL, device_type_id int4 NULL, standard_operation text NULL, estimated_duration_minutes int4 NULL, CONSTRAINT maintenance_items_pkey PRIMARY KEY (id), CONSTRAINT fk_items_type FOREIGN KEY (device_type_id) REFERENCES public.device_types(id));
                COMMENT ON TABLE public.maintenance_items IS '保养项目标准定义';
                
                
                -- public.maintenance_records definition
                
                -- Drop table
                
                -- DROP TABLE public.maintenance_records;
                
                CREATE TABLE public.maintenance_records ( id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, device_id int4 NOT NULL, technician_id int4 NOT NULL, maintenance_date timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL, duration_minutes int4 NULL, result_status varchar(20) DEFAULT 'completed'::character varying NULL, notes text NULL, next_due_date date NULL, created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL, CONSTRAINT maintenance_records_pkey PRIMARY KEY (id), CONSTRAINT maintenance_records_result_status_check CHECK (((result_status)::text = ANY ((ARRAY['completed'::character varying, 'failed'::character varying, 'pending'::character varying])::text[]))), CONSTRAINT fk_records_device FOREIGN KEY (device_id) REFERENCES public.devices(id), CONSTRAINT fk_records_technician FOREIGN KEY (technician_id) REFERENCES public.technicians(id));
                CREATE INDEX idx_records_device_date ON public.maintenance_records USING btree (device_id, maintenance_date);
                CREATE INDEX idx_records_technician ON public.maintenance_records USING btree (technician_id);
                COMMENT ON TABLE public.maintenance_records IS '设备保养执行记录';
                
                -- Column comments
                
                COMMENT ON COLUMN public.maintenance_records.result_status IS '保养结果状态';
                
                
                -- public.maintenance_record_details definition
                
                -- Drop table
                
                -- DROP TABLE public.maintenance_record_details;
                
                CREATE TABLE public.maintenance_record_details ( id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, record_id int4 NOT NULL, item_id int4 NOT NULL, check_result varchar(20) NULL, measurement_value varchar(100) NULL, remarks text NULL, CONSTRAINT maintenance_record_details_check_result_check CHECK (((check_result)::text = ANY ((ARRAY['pass'::character varying, 'fail'::character varying, 'warning'::character varying])::text[]))), CONSTRAINT maintenance_record_details_pkey PRIMARY KEY (id), CONSTRAINT fk_details_item FOREIGN KEY (item_id) REFERENCES public.maintenance_items(id), CONSTRAINT fk_details_record FOREIGN KEY (record_id) REFERENCES public.maintenance_records(id) ON DELETE CASCADE);
                COMMENT ON TABLE public.maintenance_record_details IS '保养记录详细检查项';
                """;

       var tools = new Loader(2000).fromIFunction("/home/karboom/projects/karboom/java/iSlogger/src/main/java/me/karboom/java/iSlogger/iFunction", "echarts", null);

        var agent = new Agent(UUID.randomUUID().toString(), prompt, new OpenAI("qwen-plus", new HashMap<>(), System.getenv("OPENAI_API_KEY"), "https://dashscope.aliyuncs.com/compatible-mode/v1", 3), tools) {};


        return agent;
    }

    public Tmp(String ip, Integer port) {
        super(ip, port);
    }

    @Override
    public List<String> getNodes() {
        return List.of(clusterIp + ":" + port);
    }

    public static void main(String[] args) {
        var server = new Tmp("127.0.0.1", 9088);
        server.start();
    }
}