package com.gummytranslate.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Final-sentence translator used only for the readable classroom history. */
final class QwenMtClient {
    private static final String ENDPOINT =
            "https://llm-xgyqfacpy0aysgih.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    String translate(String apiKey, String english, List<AppStorage.Caption> memory) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", "qwen-mt-flash");
        body.put("messages", new JSONArray().put(new JSONObject()
                .put("role", "user")
                .put("content", english)));
        body.put("temperature", 0.2);

        JSONObject options = new JSONObject();
        options.put("source_lang", "English");
        options.put("target_lang", "Chinese");
        options.put("domains", "This is a university classroom lecture, often about human-computer interaction and computer science. Translate into concise, fluent Simplified Chinese that a student can understand at a glance. Preserve negation, numbers, names, abbreviations and technical meaning. Avoid word-for-word translation and omit filler words only when meaning is unchanged.");
        options.put("terms", courseTerms());
        JSONArray translationMemory = new JSONArray();
        for (AppStorage.Caption caption : memory) {
            if (!caption.english.isEmpty() && !caption.chinese.isEmpty()
                    && !caption.optimizing && !caption.optimizationFailed) {
                translationMemory.put(new JSONObject()
                        .put("source", caption.english)
                        .put("target", caption.chinese));
            }
        }
        if (translationMemory.length() > 0) options.put("tm_list", translationMemory);
        body.put("translation_options", options);

        Request request = new Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            String payload = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new IOException("Qwen-MT HTTP " + response.code());
            JSONObject result = new JSONObject(payload);
            JSONArray choices = result.optJSONArray("choices");
            JSONObject choice = choices == null ? null : choices.optJSONObject(0);
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            String translated = message == null ? "" : message.optString("content", "").trim();
            if (translated.isEmpty()) throw new IOException("Qwen-MT returned no translation");
            return translated;
        }
    }

    void shutdown() {
        client.dispatcher().cancelAll();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    private JSONArray courseTerms() throws Exception {
        JSONArray terms = new JSONArray();
        addTerm(terms, "HCI", "人机交互");
        addTerm(terms, "human-computer interaction", "人机交互");
        addTerm(terms, "interaction design", "交互设计");
        addTerm(terms, "user-centered design", "以用户为中心的设计");
        addTerm(terms, "usability", "可用性");
        addTerm(terms, "ergonomics", "人体工程学");
        addTerm(terms, "interface", "界面");
        addTerm(terms, "prototype", "原型");
        addTerm(terms, "inductive reasoning", "归纳推理");
        addTerm(terms, "deductive reasoning", "演绎推理");
        return terms;
    }

    private void addTerm(JSONArray terms, String source, String target) throws Exception {
        terms.put(new JSONObject().put("source", source).put("target", target));
    }
}
