package com.hagent.ai.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hagent.ai.config.AiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 精排服务（百炼真重排）
 * 走 DashScope 嵌套体协议：请求体为 model/input{query,documents}/parameters{top_n}，
 * 响应解析 output.results[{index,relevance_score}]，按分降序并写回 metadata.rerank_score；
 * 失败时原序回退不抛错
 *
 * @author H
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerankingService {

    /** 请求超时时间（重排为同步阻塞，8s 兼顾百炼冷启动） */
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    /** 默认重排模型 */
    private static final String DEFAULT_MODEL = "qwen3.7-text-rerank";

    /** 默认百炼基址 */
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/api/v1";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 共享 HttpClient，连接与读取均为 3 秒超时 */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    private final AiConfig aiConfig;

    /**
     * 对候选文档做重排
     * 按百炼返回 relevance_score 降序排列，并在 metadata 写入 rerank_score；
     * 任一异常、非 2xx、空 results 时打 warn 日志后原序返回
     *
     * @param query 查询文本
     * @param docs 待重排文档
     * @return 按分降序文档，失败时返回原序
     * @author H
     */
    public List<Document> rerank(String query, List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return docs;
        }
        if (!StringUtils.hasText(query)) {
            return docs;
        }
        try {
            // 基址去尾部斜杠，避免拼接出双斜杠
            String baseUrl = DEFAULT_BASE_URL;
            String model = DEFAULT_MODEL;
            String apiKey = "";
            try {
                if (aiConfig != null && aiConfig.getRerank() != null) {
                    if (StringUtils.hasText(aiConfig.getRerank().getBaseUrl())) {
                        baseUrl = aiConfig.getRerank().getBaseUrl().trim();
                    }
                    if (StringUtils.hasText(aiConfig.getRerank().getModel())) {
                        model = aiConfig.getRerank().getModel().trim();
                    }
                    apiKey = aiConfig.getRerank().getApiKey();
                }
                // 重排 Key 为空时复用 embedding Key
                if (!StringUtils.hasText(apiKey) && aiConfig != null && aiConfig.getEmbedding() != null) {
                    apiKey = aiConfig.getEmbedding().getApiKey();
                }
            } catch (Exception e) {
                log.warn("读取重排配置失败，原序返回: {}", e.getMessage());
                return docs;
            }
            while (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            if (!StringUtils.hasText(apiKey)) {
                log.warn("重排 Key 为空，原序返回");
                return docs;
            }

            // 组装请求体：model + input(query/documents) + parameters.top_n
            List<String> texts = new ArrayList<>(docs.size());
            for (Document d : docs) {
                String t = d.getText() == null ? "" : d.getText();
                texts.add(t);
            }
            Map<String, Object> input = new HashMap<>();
            input.put("query", query);
            input.put("documents", texts);
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("top_n", docs.size());
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("input", input);
            body.put("parameters", parameters);
            String bodyJson = OBJECT_MAPPER.writeValueAsString(body);

            String url = baseUrl + "/services/rerank/text-rerank/text-rerank";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                log.warn("重排接口非 2xx，原序返回: status={}", status);
                return docs;
            }
            String respBody = response.body();
            if (!StringUtils.hasText(respBody)) {
                log.warn("重排响应为空，原序返回");
                return docs;
            }
            JsonNode root = OBJECT_MAPPER.readTree(respBody);
            JsonNode results = root.path("output").path("results");
            if (!results.isArray() || results.isEmpty()) {
                log.warn("重排结果为空，原序返回");
                return docs;
            }
            // 按 index 回填分数，缺失 index 的条目直接跳过
            double[] scores = new double[docs.size()];
            boolean[] hit = new boolean[docs.size()];
            for (JsonNode r : results) {
                int index = r.path("index").asInt(-1);
                double score = r.path("relevance_score").asDouble(Double.NaN);
                if (index >= 0 && index < docs.size() && !Double.isNaN(score)) {
                    scores[index] = score;
                    hit[index] = true;
                }
            }
            boolean anyHit = false;
            for (boolean h : hit) {
                if (h) {
                    anyHit = true;
                    break;
                }
            }
            if (!anyHit) {
                log.warn("重排结果无有效 index/分数，原序返回");
                return docs;
            }
            // 未命中条目给最低分，保证排序稳定且不丢文档
            double minHit = Double.MAX_VALUE;
            for (int i = 0; i < docs.size(); i++) {
                if (hit[i] && scores[i] < minHit) {
                    minHit = scores[i];
                }
            }
            for (int i = 0; i < docs.size(); i++) {
                if (!hit[i]) {
                    scores[i] = minHit - 1.0d;
                }
            }
            // 写回 metadata.rerank_score
            for (int i = 0; i < docs.size(); i++) {
                try {
                    docs.get(i).getMetadata().put("rerank_score", scores[i]);
                } catch (Exception e) {
                    log.warn("写回 rerank_score 失败 index={}: {}", i, e.getMessage());
                }
            }
            // 按分降序返回新列表，不改原列表顺序语义（原列表已写分，仅返回排序视图）
            List<Document> sorted = new ArrayList<>(docs);
            sorted.sort(Comparator.comparingDouble((Document d) -> {
                Object v = d.getMetadata() != null ? d.getMetadata().get("rerank_score") : null;
                if (v instanceof Number n) {
                    return n.doubleValue();
                }
                try {
                    return Double.parseDouble(String.valueOf(v));
                } catch (Exception e) {
                    return Double.NEGATIVE_INFINITY;
                }
            }).reversed());
            return sorted;
        } catch (Exception e) {
            log.warn("重排调用失败，原序返回: {}", e.getMessage());
            return docs;
        }
    }
}
