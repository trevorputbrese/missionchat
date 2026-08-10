package gov.state.missionchat.documentchat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentsRagService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentsRagService.class);
    private static final String MODE_RAG = "RAG";
    private static final String MODE_CHAT_ONLY = "CHAT_ONLY";
    private static final String DEFAULT_TABLE_NAME = "localdocs";
    private static final Pattern VECTOR_DIMENSION_PATTERN = Pattern.compile("^vector\\((\\d+)\\)$");

    private final DocumentsRagProperties properties;
    private final TokenTextSplitter splitter;
    private final String tableName;
    private JdbcTemplate jdbcTemplate;
    private VectorStore vectorStore;

    private volatile DocumentsRagStatusResponse status;

    public DocumentsRagService(ObjectProvider<DataSource> dataSourceProvider,
                               ObjectProvider<EmbeddingModel> embeddingModelProvider,
                               DocumentsRagProperties properties) {
        this.properties = properties;
        this.tableName = sanitizeTableName(properties.getTableName());
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(properties.getChunkSize())
                .withMinChunkSizeChars(properties.getMinChunkSizeChars())
                .withMinChunkLengthToEmbed(properties.getMinChunkLengthToEmbed())
                .withMaxNumChunks(properties.getMaxNumChunks())
                .build();

        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            this.jdbcTemplate = null;
            this.vectorStore = null;
            this.status = unavailable("RAG unavailable: no database binding found.");
            return;
        }

        if (isH2DataSource(dataSource)) {
            this.jdbcTemplate = null;
            this.vectorStore = null;
            this.status = unavailable("RAG unavailable: no Postgres database binding found.");
            return;
        }

        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            this.jdbcTemplate = null;
            this.vectorStore = null;
            this.status = unavailable("RAG unavailable: no embedding model is configured.");
            return;
        }

        this.jdbcTemplate = new JdbcTemplate(dataSource);

        try {
            reconcileVectorDimensions(embeddingModel);

            PgVectorStore store = PgVectorStore.builder(this.jdbcTemplate, embeddingModel)
                    .vectorTableName(this.tableName)
                    .initializeSchema(true)
                    .build();
            store.afterPropertiesSet();

            this.vectorStore = store;
            this.status = new DocumentsRagStatusResponse(
                    true,
                    MODE_RAG,
                    "RAG available with configured embedding model."
            );

            LOGGER.info("DocumentsChat RAG initialized with table '{}'", this.tableName);
        } catch (Exception ex) {
            this.vectorStore = null;
            this.status = unavailable("RAG unavailable: " + simplifyMessage(ex));
            LOGGER.warn("DocumentsChat RAG disabled during startup: {}", simplifyMessage(ex));
        }
    }

    public DocumentsRagStatusResponse status() {
        return this.status;
    }

    public boolean isAvailable() {
        return this.vectorStore != null && this.status.available();
    }

    public DocumentsUploadResponse indexDocuments(MultipartFile[] files) {
        if (!isAvailable()) {
            throw new IllegalStateException(this.status.message());
        }
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("At least one file is required.");
        }

        String uploadId = UUID.randomUUID().toString();
        List<Document> allChunks = new ArrayList<>();
        Set<String> fileNames = new LinkedHashSet<>();
        int filesIndexed = 0;

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }

            String fileName = normalizeFileName(file.getOriginalFilename());
            List<Document> chunkedDocuments = readAndChunk(file, fileName, uploadId);
            if (chunkedDocuments.isEmpty()) {
                continue;
            }

            allChunks.addAll(chunkedDocuments);
            fileNames.add(fileName);
            filesIndexed++;
        }

        if (allChunks.isEmpty()) {
            throw new IllegalArgumentException("No readable text content was found in the uploaded files.");
        }

        this.vectorStore.add(allChunks);

        return new DocumentsUploadResponse(
                filesIndexed,
                allChunks.size(),
                List.copyOf(fileNames),
                "Indexed " + allChunks.size() + " chunks from " + filesIndexed + " file(s)."
        );
    }

    public DocumentsDocumentListResponse listDocuments() {
        if (this.jdbcTemplate == null) {
            return new DocumentsDocumentListResponse(List.of(), 0);
        }

        try {
            List<String> documents = this.jdbcTemplate.query(
                    "SELECT DISTINCT metadata->>'fileName' AS file_name FROM " + this.tableName
                            + " WHERE metadata->>'fileName' IS NOT NULL ORDER BY file_name",
                    (rs, rowNum) -> rs.getString("file_name")
            );
            Long chunkCount = this.jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + this.tableName,
                    Long.class
            );
            return new DocumentsDocumentListResponse(documents, chunkCount == null ? 0 : chunkCount);
        } catch (Exception ex) {
            LOGGER.debug("Could not list indexed documents from {}: {}", this.tableName, simplifyMessage(ex));
            return new DocumentsDocumentListResponse(List.of(), 0);
        }
    }

    public DocumentsDocumentListResponse clearDocuments() {
        if (!isAvailable()) {
            return new DocumentsDocumentListResponse(List.of(), 0);
        }

        try {
            this.jdbcTemplate.execute("TRUNCATE TABLE " + this.tableName);
            return new DocumentsDocumentListResponse(List.of(), 0);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not clear indexed documents.", ex);
        }
    }

    public DocumentsRagContext retrieveContext(String userQuery) {
        if (!isAvailable() || userQuery == null || userQuery.isBlank()) {
            return DocumentsRagContext.empty();
        }

        try {
            List<Document> matches = this.vectorStore.similaritySearch(SearchRequest.builder()
                    .query(userQuery)
                    .topK(this.properties.getTopK())
                    .similarityThresholdAll()
                    .build());

            if (matches == null || matches.isEmpty()) {
                return DocumentsRagContext.empty();
            }

            String contextBlock = matches.stream()
                    .map(Document::getText)
                    .filter(text -> text != null && !text.isBlank())
                    .collect(Collectors.joining("\n\n---\n\n"));

            if (contextBlock.isBlank()) {
                return DocumentsRagContext.empty();
            }

            List<String> citations = matches.stream()
                    .map(document -> stringify(document.getMetadata().get("fileName")))
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .toList();

            return new DocumentsRagContext(contextBlock, citations);
        } catch (Exception ex) {
            LOGGER.warn("DocumentsChat retrieval failed; falling back to chat-only mode for this request: {}",
                    simplifyMessage(ex));
            return DocumentsRagContext.empty();
        }
    }

    private List<Document> readAndChunk(MultipartFile file, String fileName, String uploadId) {
        List<Document> rawDocuments = readRawDocuments(file, fileName, uploadId);
        if (rawDocuments.isEmpty()) {
            return List.of();
        }

        List<Document> splitDocuments = this.splitter.apply(rawDocuments);
        List<Document> preparedDocuments = new ArrayList<>();

        int chunkIndex = 0;
        for (Document document : splitDocuments) {
            String text = document.getText();
            if (text == null || text.isBlank()) {
                continue;
            }

            Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
            metadata.put("fileName", fileName);
            metadata.put("uploadId", uploadId);
            metadata.put("chunkIndex", chunkIndex++);
            metadata.put("uploadedAt", Instant.now().toString());

            preparedDocuments.add(new Document(document.getId(), text, metadata));
        }

        return preparedDocuments;
    }

    private List<Document> readRawDocuments(MultipartFile file, String fileName, String uploadId) {
        String contentType = file.getContentType();
        Map<String, Object> baseMetadata = new LinkedHashMap<>();
        baseMetadata.put("fileName", fileName);
        baseMetadata.put("mimeType", contentType == null ? "application/octet-stream" : contentType);
        baseMetadata.put("uploadId", uploadId);
        baseMetadata.put("uploadedAt", Instant.now().toString());

        try {
            byte[] bytes = file.getBytes();
            ByteArrayResource resource = namedResource(bytes, fileName);

            List<Document> documents;
            if (isPdf(fileName, contentType)) {
                PagePdfDocumentReader reader = new PagePdfDocumentReader(resource);
                documents = reader.get();
            } else if (isText(fileName, contentType)) {
                TextReader reader = new TextReader(resource);
                documents = reader.get();
            } else {
                throw new IllegalArgumentException("Unsupported file type for '" + fileName + "'. Only PDF and text are supported.");
            }

            return mergeMetadata(documents, baseMetadata);
        } catch (IOException ioException) {
            throw new IllegalArgumentException("Could not read file '" + fileName + "'.", ioException);
        }
    }

    private static List<Document> mergeMetadata(List<Document> documents, Map<String, Object> extraMetadata) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        List<Document> merged = new ArrayList<>();
        for (Document document : documents) {
            String text = document.getText();
            if (text == null || text.isBlank()) {
                continue;
            }

            Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
            metadata.putAll(extraMetadata);
            merged.add(new Document(document.getId(), text, metadata));
        }
        return merged;
    }

    private static ByteArrayResource namedResource(byte[] bytes, String fileName) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private static boolean isPdf(String fileName, String contentType) {
        return "application/pdf".equalsIgnoreCase(contentType)
                || fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static boolean isText(String fileName, String contentType) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/")) {
            return true;
        }
        String normalized = fileName.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".txt") || normalized.endsWith(".text") || normalized.endsWith(".md");
    }

    private static String sanitizeTableName(String configuredTableName) {
        if (configuredTableName == null) {
            return DEFAULT_TABLE_NAME;
        }

        String candidate = configuredTableName.trim();
        if (candidate.isBlank() || !candidate.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            return DEFAULT_TABLE_NAME;
        }
        return candidate;
    }

    private static String normalizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "upload-" + UUID.randomUUID() + ".txt";
        }
        return fileName.trim();
    }

    private static DocumentsRagStatusResponse unavailable(String message) {
        return new DocumentsRagStatusResponse(false, MODE_CHAT_ONLY, message);
    }

    private static String stringify(Object value) {
        if (value == null) {
            return null;
        }
        String textValue = value.toString();
        return textValue.isBlank() ? null : textValue;
    }

    private static String simplifyMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "unexpected error";
        }
        return throwable.getMessage();
    }

    private static boolean isH2DataSource(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String jdbcUrl = connection.getMetaData().getURL();
            return jdbcUrl != null && jdbcUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:h2:");
        } catch (SQLException ex) {
            return false;
        }
    }

    private void reconcileVectorDimensions(EmbeddingModel embeddingModel) {
        Integer modelDimensions = resolveModelDimensions(embeddingModel);
        if (modelDimensions == null || modelDimensions <= 0) {
            return;
        }

        Integer tableDimensions = readExistingTableVectorDimensions();
        if (tableDimensions == null || tableDimensions.equals(modelDimensions)) {
            return;
        }

        LOGGER.warn("DocumentsChat detected vector dimension mismatch for table '{}': table={}, model={}. Recreating table.",
                this.tableName, tableDimensions, modelDimensions);
        this.jdbcTemplate.execute("DROP TABLE IF EXISTS " + this.tableName);
    }

    private Integer resolveModelDimensions(EmbeddingModel embeddingModel) {
        try {
            int dimensions = embeddingModel.dimensions();
            return dimensions > 0 ? dimensions : null;
        } catch (Exception ex) {
            LOGGER.warn("Could not determine embedding model dimensions during startup: {}", simplifyMessage(ex));
            return null;
        }
    }

    private Integer readExistingTableVectorDimensions() {
        try {
            String vectorType = this.jdbcTemplate.query(
                    "SELECT format_type(a.atttypid, a.atttypmod) "
                            + "FROM pg_attribute a "
                            + "JOIN pg_class c ON c.oid = a.attrelid "
                            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                            + "WHERE n.nspname = 'public' "
                            + "AND c.relname = ? "
                            + "AND a.attname = 'embedding' "
                            + "AND a.attnum > 0 "
                            + "AND NOT a.attisdropped",
                    ps -> ps.setString(1, this.tableName),
                    rs -> rs.next() ? rs.getString(1) : null
            );

            if (vectorType == null) {
                return null;
            }

            Matcher matcher = VECTOR_DIMENSION_PATTERN.matcher(vectorType);
            return matcher.matches() ? Integer.parseInt(matcher.group(1)) : null;
        } catch (Exception ex) {
            LOGGER.debug("Could not inspect existing vector dimensions for {}: {}", this.tableName, simplifyMessage(ex));
            return null;
        }
    }
}
