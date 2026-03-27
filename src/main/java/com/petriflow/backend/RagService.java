package com.petriflow.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import okhttp3.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.*;

/**
 * RAG service — splits the Petriflow guide into chunks, embeds them once,
 * and retrieves the top-K most relevant chunks per query.
 *
 * Retrieval strategy:
 *   1. Business query     — cosine search on what the user asked for
 *   2. Mechanical queries — static queries covering Petriflow mechanics that
 *                           are never mentioned in business descriptions but
 *                           are always required for correct generation.
 *                           Their embeddings are precomputed at startup and
 *                           cached alongside chunk embeddings.
 *   3. Keyword queries    — dynamic queries triggered by pattern keywords
 *                           detected in the user message (parallel, anonymous,
 *                           conditional, taskRef, etc.)
 *   4. Always-include     — sections 7, 8, 9 pinned unconditionally.
 *
 * Results from all queries are deduplicated, capped at ragTopK chunks total,
 * then always-include sections are appended if not already present.
 *
 * Embedding provider is selected via AppConfig.embedProvider:
 *   "openai" → text-embedding-3-small  (batched, fast)
 *   "gemini" → gemini-embedding-001    (sequential with 50ms delay)
 *
 * Cache layout (per provider):
 *   rag-cache/<provider>/chunks.json
 *   rag-cache/<provider>/embeddings.json
 *   rag-cache/<provider>/mechanical_embeddings.json
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    // ── Embedding endpoints ───────────────────────────────────────────────────

    private static final String OPENAI_EMBED_URL   = "https://api.openai.com/v1/embeddings";
    private static final String OPENAI_EMBED_MODEL = "text-embedding-3-small";

    private static final String GEMINI_EMBED_URL   =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:embedContent?key=%s";
    private static final String GEMINI_EMBED_MODEL = "gemini-embedding-001";

    private static final String CACHE_BASE = "rag-cache";

    // ── Static mechanical queries ─────────────────────────────────────────────
    //
    // These cover Petriflow mechanics that are always required but never appear
    // in business-domain user messages.  Every generation retrieves all of them.
    //
    // Each entry is: { label, query-text }
    // label is used only for logging.

    private static final String[][] MECHANICAL_QUERIES = {
            { "system-task-firing",
                    "system role task async.run assignTask finishTask firing execution" },
            { "transition-priority",
                    "transition priority label id x y roleRef required field every transition" },
            { "action-phase",
                    "event finish phase pre post action placement routing token" },
            { "import-header",
                    "action import header field reference f.fieldId t.transitionId semicolon" },
            { "variable-arc-syntax",
                    "variable arc regular type reference number field multiplicity zero routing transition source" },
            { "xml-event-syntax",
                    "event type finish actions phase pre post CDATA action id structure" },
    };

    // ── Keyword → targeted query mappings ────────────────────────────────────
    //
    // If any keyword in the set is found in the (lowercased) user query,
    // the corresponding targeted query is added to the retrieval pool.

    private static final Object[][] KEYWORD_QUERIES = {
            // keywords                                      targeted query
            { new String[]{ "parallel", "and-split", "and-join", "simultaneous", "concurrent" },
                    "AND-split AND-join parallel pattern system role fire all branches" },

            { new String[]{ "anonymous", "public", "external", "unauthenticated" },
                    "anonymous role public submission anonymousRole defaultRole roleRef" },

            { new String[]{ "condition", "branch", "decision", "route", "xor", "exclusive", "approve", "reject" },
                    "variable arc XOR fork exclusive choice routing transition number field" },

            { new String[]{ "or-split", "or-join", "inclusive", "multichoice", "multiple departments", "optional branch" },
                    "OR-split OR-join inclusive multichoice variable arc number routing field" },

            { new String[]{ "taskref", "embed", "embedded", "panel", "inline form" },
                    "taskRef embedded task panel read arc caseEvents create post async.run" },

            { new String[]{ "detail", "status", "tracking", "progress", "always visible" },
                    "persistent detail view read arc status tracking always-on task" },

            { new String[]{ "loop", "revision", "resubmit", "rework", "iterate" },
                    "loop revision resubmit arc back place token pattern" },

            { new String[]{ "escalat", "timeout", "deadline", "overdue" },
                    "escalation timeout deadline pattern systematic task" },

            { new String[]{ "email", "notify", "notification", "sendmail" },
                    "email notification sendEmail action service" },

            { new String[]{ "child", "spawn", "subprocess", "inter-process", "case create" },
                    "child process spawn inter-process communication caseRef createCase" },

            { new String[]{ "voting", "consensus", "n of m", "majority" },
                    "voting consensus N of M pattern approval" },

            { new String[]{ "four-eyes", "two-person", "dual approval" },
                    "four-eyes principle dual approval pattern" },

            { new String[]{ "enumeration", "dropdown", "select", "options", "choice" },
                    "enumeration field options key value component select list" },

            { new String[]{ "file", "upload", "attachment", "document upload" },
                    "file upload attachment field type component" },
    };

    // ── State ─────────────────────────────────────────────────────────────────

    @Autowired
    private AppConfig config;

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http   = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private List<String>   chunks;
    private List<double[]> embeddings;
    private List<double[]> mechanicalEmbeddings;   // parallel to MECHANICAL_QUERIES
    private List<String>   alwaysInclude;

    // ── Init ──────────────────────────────────────────────────────────────────

    @PostConstruct
    public void init() throws Exception {
        if (!config.isRagMode()) {
            log.info("RAG disabled (context.mode={}), skipping init", config.contextMode);
            return;
        }
        initWithProvider(config.embedProvider);
    }

    private void initWithProvider(String provider) throws Exception {
        String cacheDir   = CACHE_BASE + "/" + provider.toLowerCase();
        String chunksFile = cacheDir + "/chunks.json";
        String embsFile   = cacheDir + "/embeddings.json";
        String mechFile   = cacheDir + "/mechanical_embeddings.json";

        if (Files.exists(Paths.get(chunksFile))
                && Files.exists(Paths.get(embsFile))
                && Files.exists(Paths.get(mechFile))) {
            log.info("Loading RAG cache ({})...", provider);
            loadCache(chunksFile, embsFile, mechFile);
        } else {
            log.info("No cache for provider '{}' — computing embeddings (one-time)...", provider);
            String guide = loadGuide();
            chunks = splitIntoChunks(guide);
            log.info("Split guide into {} chunks", chunks.size());
            embeddings = computeEmbeddings(chunks, provider);

            // Precompute mechanical query embeddings
            List<String> mechTexts = Arrays.stream(MECHANICAL_QUERIES)
                    .map(q -> q[1])
                    .collect(Collectors.toList());
            log.info("Precomputing {} mechanical query embeddings...", mechTexts.size());
            mechanicalEmbeddings = computeEmbeddings(mechTexts, provider);

            saveCache(cacheDir, chunksFile, embsFile, mechFile);
        }
        alwaysInclude = buildAlwaysInclude();
        log.info("RAG ready [{}]: {} chunks, {} mechanical queries, {} always-include sections",
                provider, chunks.size(), MECHANICAL_QUERIES.length, alwaysInclude.size());
    }

    // ── Retrieve ──────────────────────────────────────────────────────────────

    /**
     * Multi-query retrieval:
     *   1. Business query (user message) → top-3 chunks
     *   2. All mechanical queries (precomputed) → top-2 chunks each
     *   3. Keyword-triggered targeted queries → top-2 chunks each
     *   4. Always-include sections appended unconditionally
     *
     * All results are deduplicated. Total capped at ragTopK before always-include.
     */
    public String retrieve(String query) throws Exception {
        // Lazy init if RAG was enabled dynamically
        if (chunks == null || embeddings == null || mechanicalEmbeddings == null) {
            log.warn("RAG not initialized, initializing now with provider '{}'", config.embedProvider);
            initWithProvider(config.embedProvider);
        }

        // Preserve insertion order, deduplicate by chunk index
        LinkedHashSet<Integer> selectedIndices = new LinkedHashSet<>();

        // 1. Business query
        double[] businessEmb = embedSingle(query, config.embedProvider);
        topK(businessEmb, 3).forEach(selectedIndices::add);
        log.debug("Business query added {} indices", selectedIndices.size());

        // 2. Mechanical queries — use precomputed embeddings, no API call
        for (int i = 0; i < MECHANICAL_QUERIES.length; i++) {
            List<Integer> mechTop = topK(mechanicalEmbeddings.get(i), 2);
            selectedIndices.addAll(mechTop);
            log.debug("Mechanical query '{}' → chunks {}", MECHANICAL_QUERIES[i][0], mechTop);
        }

        // 3. Keyword-triggered queries
        String lowerQuery = query.toLowerCase();
        for (Object[] entry : KEYWORD_QUERIES) {
            String[] keywords = (String[]) entry[0];
            String   targeted = (String)   entry[1];
            boolean  matched  = Arrays.stream(keywords).anyMatch(lowerQuery::contains);
            if (matched) {
                double[] kEmb = embedSingle(targeted, config.embedProvider);
                List<Integer> kTop = topK(kEmb, 2);
                selectedIndices.addAll(kTop);
                log.debug("Keyword query '{}' triggered → chunks {}", targeted.substring(0, 30), kTop);
            }
        }

        // Cap at ragTopK
        List<String> retrieved = selectedIndices.stream()
                .limit(config.ragTopK)
                .map(chunks::get)
                .collect(Collectors.toList());

        // 4. Always-include (appended, not counted against ragTopK cap)
        for (String sec : alwaysInclude) {
            if (!retrieved.contains(sec)) retrieved.add(sec);
        }

        String context = String.join("\n\n---\n\n", retrieved);
        log.info("RAG retrieved {} chunks (~{} tokens) for: {}",
                retrieved.size(),
                context.length() / 4,
                query.substring(0, Math.min(80, query.length())));
        return context;
    }

    /**
     * Returns indices of top-k chunks by cosine similarity to the given embedding.
     */
    private List<Integer> topK(double[] queryEmb, int k) {
        List<ScoredChunk> scored = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            scored.add(new ScoredChunk(i, cosineSim(queryEmb, embeddings.get(i))));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.stream().limit(k).map(s -> s.index).collect(Collectors.toList());
    }

    /** Force recompute — call via POST /api/reload after updating petriflow_reference.md */
    public void reload() throws Exception {
        log.info("Reloading RAG embeddings for provider '{}'...", config.embedProvider);
        String cacheDir = CACHE_BASE + "/" + config.embedProvider.toLowerCase();
        deleteCache(cacheDir);
        initWithProvider(config.embedProvider);
    }

    // ── Embedding dispatch ────────────────────────────────────────────────────

    private List<double[]> computeEmbeddings(List<String> texts, String provider) throws Exception {
        if ("openai".equalsIgnoreCase(provider)) {
            return embedBatchOpenAI(texts);
        } else {
            return embedSequentialGemini(texts);
        }
    }

    private double[] embedSingle(String text, String provider) throws Exception {
        if ("openai".equalsIgnoreCase(provider)) {
            return embedBatchOpenAI(Collections.singletonList(text)).get(0);
        } else {
            return embedSingleGemini(text);
        }
    }

    // ── OpenAI embeddings ─────────────────────────────────────────────────────

    private List<double[]> embedBatchOpenAI(List<String> texts) throws Exception {
        List<double[]> result = new ArrayList<>();
        int batchSize = 100;
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            ObjectNode body = mapper.createObjectNode();
            body.put("model", OPENAI_EMBED_MODEL);
            ArrayNode input = mapper.createArrayNode();
            batch.forEach(input::add);
            body.set("input", input);

            Request req = new Request.Builder()
                    .url(OPENAI_EMBED_URL)
                    .header("Authorization", "Bearer " + config.openaiApiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                if (resp.code() != 200)
                    throw new IOException("OpenAI embed error " + resp.code() + ": " + resp.body().string());

                JsonNode json = mapper.readTree(resp.body().string());
                for (JsonNode item : json.get("data")) {
                    JsonNode emb = item.get("embedding");
                    double[] arr = new double[emb.size()];
                    for (int j = 0; j < emb.size(); j++) arr[j] = emb.get(j).asDouble();
                    result.add(arr);
                }
                log.info("OpenAI embedded {}/{}", Math.min(i + batchSize, texts.size()), texts.size());
            }
        }
        return result;
    }

    // ── Gemini embeddings ─────────────────────────────────────────────────────

    private List<double[]> embedSequentialGemini(List<String> texts) throws Exception {
        List<double[]> result = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            result.add(embedSingleGemini(texts.get(i)));
            if ((i + 1) % 10 == 0) log.info("Gemini embedded {}/{}", i + 1, texts.size());
            Thread.sleep(50);
        }
        return result;
    }

    private double[] embedSingleGemini(String text) throws Exception {
        ObjectNode body    = mapper.createObjectNode();
        ObjectNode content = mapper.createObjectNode();
        ArrayNode  parts   = mapper.createArrayNode();
        ObjectNode part    = mapper.createObjectNode();
        part.put("text", text);
        parts.add(part);
        content.set("parts", parts);
        body.set("content", content);

        String url = String.format(GEMINI_EMBED_URL, GEMINI_EMBED_MODEL, config.geminiApiKey);
        Request req = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() == 429) {
                log.warn("Gemini embed rate limited, waiting 5s...");
                Thread.sleep(5000);
                return embedSingleGemini(text);
            }
            if (resp.code() != 200)
                throw new IOException("Gemini embed error " + resp.code() + ": " + resp.body().string());

            JsonNode values = mapper.readTree(resp.body().string()).path("embedding").path("values");
            double[] arr = new double[values.size()];
            for (int i = 0; i < values.size(); i++) arr[i] = values.get(i).asDouble();
            return arr;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String loadGuide() throws IOException {
        ClassPathResource res = new ClassPathResource("guides/petriflow_reference.md");
        return new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private List<String> splitIntoChunks(String text) {
        String[] parts = text.split("(?m)(?=^#{1,3} )");
        return Arrays.stream(parts)
                .map(String::trim)
                .filter(s -> s.length() > 100)
                .collect(Collectors.toList());
    }

    private List<String> buildAlwaysInclude() {
        String[] prefixes = config.ragAlwaysInclude.split(",");
        return chunks.stream()
                .filter(c -> {
                    for (String prefix : prefixes) {
                        if (c.matches("(?s)^## " + prefix.trim().replace(".", "\\.") + ".*"))
                            return true;
                    }
                    return false;
                })
                .collect(Collectors.toList());
    }

    private double cosineSim(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na  += a[i] * a[i];
            nb  += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-10);
    }

    // ── Cache I/O ─────────────────────────────────────────────────────────────

    private void saveCache(String cacheDir, String chunksFile,
                           String embsFile, String mechFile) throws IOException {
        Files.createDirectories(Paths.get(cacheDir));
        mapper.writeValue(new File(chunksFile), chunks);
        mapper.writeValue(new File(embsFile),   embeddings);
        mapper.writeValue(new File(mechFile),   mechanicalEmbeddings);
        log.info("Cache saved to {}/", cacheDir);
    }

    private void loadCache(String chunksFile, String embsFile,
                           String mechFile) throws IOException {
        chunks = mapper.readValue(new File(chunksFile),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));

        List<List<Double>> rawChunks = mapper.readValue(new File(embsFile),
                mapper.getTypeFactory().constructCollectionType(List.class,
                        mapper.getTypeFactory().constructCollectionType(List.class, Double.class)));
        embeddings = rawChunks.stream()
                .map(l -> l.stream().mapToDouble(Double::doubleValue).toArray())
                .collect(Collectors.toList());

        List<List<Double>> rawMech = mapper.readValue(new File(mechFile),
                mapper.getTypeFactory().constructCollectionType(List.class,
                        mapper.getTypeFactory().constructCollectionType(List.class, Double.class)));
        mechanicalEmbeddings = rawMech.stream()
                .map(l -> l.stream().mapToDouble(Double::doubleValue).toArray())
                .collect(Collectors.toList());

        log.info("Loaded {} chunks + {} mechanical embeddings from cache",
                chunks.size(), mechanicalEmbeddings.size());
    }

    private void deleteCache(String cacheDir) throws IOException {
        for (String f : new String[]{ "/chunks.json", "/embeddings.json", "/mechanical_embeddings.json" }) {
            Path p = Paths.get(cacheDir + f);
            if (Files.exists(p)) Files.delete(p);
        }
        log.info("Deleted cache in {}/", cacheDir);
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    static class ScoredChunk {
        final int index;
        final double score;
        ScoredChunk(int i, double s) { index = i; score = s; }
    }
}