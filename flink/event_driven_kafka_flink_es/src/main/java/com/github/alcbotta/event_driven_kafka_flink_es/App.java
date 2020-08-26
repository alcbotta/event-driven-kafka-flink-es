package com.github.alcbotta.event_driven_kafka_flink_es;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.type.TypeReference;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchSinkFunction;
import org.apache.flink.streaming.connectors.elasticsearch.RequestIndexer;
import org.apache.flink.streaming.connectors.elasticsearch7.ElasticsearchSink;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.http.HttpHost;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Requests;

public class App {
    public static void main(String[] args) throws Exception {
        final StreamExecutionEnvironment see = StreamExecutionEnvironment.getExecutionEnvironment();
        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", "kafka-server:9092");
        properties.setProperty("group.id", "test");
        FlinkKafkaConsumer<String> kafkaSource =
                new FlinkKafkaConsumer<>("car-makers", new SimpleStringSchema(), properties);
        DataStream<String> stream = see.addSource(kafkaSource);
        DataStream<Map<String, Object>> jsonStream =
                stream.map(new MapFunction<String, Map<String, Object>>() {
                    @Override
                    public Map<String, Object> map(String value) throws Exception {
                        ObjectMapper mapper = new ObjectMapper();
                        TypeReference<HashMap<String, Object>> typeRef =
                                new TypeReference<HashMap<String, Object>>() {
                                };
                        Map<String, Object> obj = mapper.readValue(value, typeRef);

                        if (obj.containsKey("maker")) {
                            String maker = (String) obj.get("maker");
                            obj.put("maker", maker.toLowerCase().replace(" ", "_"));
                        }
                        return obj;
                    }
                });


        KeyedStream<Map<String, Object>, String> keyedStream =
                jsonStream.keyBy(new KeySelector<Map<String, Object>, String>() {
                    @Override
                    public String getKey(Map<String, Object> obj) throws Exception {
                        return (String) obj.get("maker");
                    }
                });

        List<HttpHost> httpHosts = new ArrayList<>();
        httpHosts.add(new HttpHost("elasticsearch", 9200, "http"));
        ElasticsearchSink.Builder<Map<String, Object>> esSinkBuilder =
                new ElasticsearchSink.Builder<Map<String, Object>>(httpHosts,
                        new ElasticsearchSinkFunction<Map<String, Object>>() {
                            public IndexRequest createIndexRequest(Map<String, Object> element) {
                                return Requests.indexRequest().index((String) element.get("maker"))
                                        .source(element);
                            }

                            @Override
                            public void process(Map<String, Object> element, RuntimeContext ctx,
                                    RequestIndexer indexer) {
                                indexer.add(createIndexRequest(element));
                            }
                        });


        esSinkBuilder.setBulkFlushMaxActions(1);
        keyedStream.addSink(esSinkBuilder.build());
        see.execute("CustomerRegistrationApp");
    }
}
