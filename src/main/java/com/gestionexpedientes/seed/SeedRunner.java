package com.gestionexpedientes.seed;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gestionexpedientes.counter.service.CounterService;
import com.gestionexpedientes.demanda.service.BpmnAreas;
import com.gestionexpedientes.file.FileContainer;
import com.gestionexpedientes.file.service.BlobStorage;
import com.gestionexpedientes.tipodemanda.TipoDemanda;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Carga los datos demo (catalogo, workflows, usuarios y expedientes con historial) en la base configurada en MONGODB_DATABASE.
 *
 * Uso:  mvn spring-boot:run -Dspring-boot.run.profiles=seed
 *
 * - Por defecto solo carga colecciones vacias. Con SEED_RESET=true borra y recarga.
 * - Usuarios demo con contrasenas definidas en seed/users.json (datos ficticios).
 * - Los BPMN de los workflows se suben a workflow-bpmn y la copia de cada expediente a demanda-bpmn.
 * - Las fechas de los expedientes son relativas al momento del seed, asi la demo siempre parece reciente.
 * - No borra blobs: despues de un reset hay que limpiar a mano los huerfanos de Blob Storage.
 */
@Component
@Profile("seed")
public class SeedRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(SeedRunner.class);

    private static final String WORKFLOW_CONTAINER = "workflow-bpmn";
    private static final String DEMANDA_CONTAINER = "demanda-bpmn";
    private static final String COUNTERS = "counters";

    private final MongoTemplate mongoTemplate;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final ConfigurableApplicationContext context;
    private final BlobStorage blobStorage;

    @Value("${seed.reset:false}")
    private boolean reset;

    public SeedRunner(MongoTemplate mongoTemplate, PasswordEncoder passwordEncoder, ObjectMapper objectMapper,
                      ConfigurableApplicationContext context, BlobStorage blobStorage) {
        this.context = context;
        this.blobStorage = blobStorage;
        this.mongoTemplate = mongoTemplate;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) throws Exception {
        logger.info("Seed sobre base '{}' (reset={})", mongoTemplate.getDb().getName(), reset);

        for (FileContainer fileContainer : FileContainer.values())
            container(fileContainer.getContainerName());

        seed("area", "com.gestionexpedientes.area.entity.AreaEntity", () -> load("area"));
        seed("tipologia", "com.gestionexpedientes.tipologia.entity.TipologiaEntity", () -> load("tipologia"));
        seed("subtipologia", "com.gestionexpedientes.subtipologia.entity.SubTipologiaEntity", () -> load("subtipologia"));
        seed("workflow", "com.gestionexpedientes.workflow.entity.WorkflowEntity", this::workflows);
        seed("users", "com.gestionexpedientes.user.entity.UserEntity", this::users);
        seedDemandas();

        createIndexes();
        logger.info("Seed finalizado.");
        if (reset) {
            logger.warn("SEED_RESET aplicado: los blobs anteriores a este seed quedaron huerfanos en Azure Blob Storage.");
            logger.warn("Limpieza: correr _auditoria/limpiar-blobs.ps1 (lista) y despues con -Borrar.");
            logger.warn("Si el script no existe: borrar en workflow-bpmn, demanda-bpmn y demanda-imagen todo blob con fecha anterior a workflow-01.bpmn.");
        }
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private boolean shouldSeed(String collection) {
        boolean hasData = mongoTemplate.collectionExists(collection) && mongoTemplate.getCollection(collection).countDocuments() > 0;
        if (hasData && !reset) {
            logger.warn("  {}: ya tiene datos, se omite (usar SEED_RESET=true para recargar)", collection);
            return false;
        }
        return true;
    }

    private void seed(String collection, String entityClass, Rows rows) throws Exception {
        if (!shouldSeed(collection))
            return;
        insert(collection, entityClass, rows.get());
    }

    private void insert(String collection, String entityClass, List<Map<String, Object>> rows) {
        mongoTemplate.dropCollection(collection);
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(CounterService.ID_PREFIX + collection)), COUNTERS);

        List<Document> docs = rows.stream()
                .map(row -> new Document(row).append("_class", entityClass))
                .collect(Collectors.toList());
        mongoTemplate.getCollection(collection).insertMany(docs);
        logger.info("  {}: {} documentos", collection, docs.size());
    }

    private List<Map<String, Object>> workflows() throws Exception {
        List<Map<String, Object>> rows = load("workflow");
        BlobContainerClient container = container(WORKFLOW_CONTAINER);

        for (Map<String, Object> row : rows) {
            String blobName = (String) row.remove("bpmnBlob");
            row.put("bpmn", upload(container, blobName, "seed/bpmn/" + blobName));
        }
        return rows;
    }

    private List<Map<String, Object>> users() throws Exception {
        List<Map<String, Object>> rows = load("users");
        rows.forEach(row -> {
            row.put("username", row.get("email"));
            row.put("password", passwordEncoder.encode((String) row.get("password")));
        });
        return rows;
    }

    @SuppressWarnings("unchecked")
    private void seedDemandas() throws Exception {
        if (!shouldSeed("demanda"))
            return;

        Map<Integer, Map<String, Object>> workflows = load("workflow").stream()
                .collect(Collectors.toMap(row -> (Integer) row.get("_id"), row -> row));
        BlobContainerClient container = container(DEMANDA_CONTAINER);
        long now = System.currentTimeMillis();

        List<Map<String, Object>> demandas = new ArrayList<>();
        List<Map<String, Object>> historial = new ArrayList<>();
        Map<String, Long> secuencias = new TreeMap<>();

        List<Map<String, Object>> rows = load("demanda");
        rows.sort((a, b) -> Integer.compare((Integer) b.get("minutosAtras"), (Integer) a.get("minutosAtras")));

        for (Map<String, Object> row : rows) {
            int id = demandas.size() + 1;
            Map<String, Object> workflow = workflows.get((Integer) row.get("idWorkflow"));
            int idUsuario = (Integer) row.get("idUsuario");
            Date fechaCreacion = haceMinutos(now, row.get("minutosAtras"));

            String caratula = caratula(workflow, fechaCreacion, secuencias);
            String blobName = "demanda" + id + ".bpmn";
            String urlBpmn = upload(container, blobName, "seed/bpmn/" + workflow.get("bpmnBlob"));

            historial.add(historial(historial.size() + 1, idUsuario, id, "Inicio", 1, null, fechaCreacion));

            String paso = "Inicio";
            int estado = 1;
            for (Map<String, Object> movimiento : (List<Map<String, Object>>) row.get("movimientos")) {
                paso = (String) movimiento.get("paso");
                estado = (Integer) movimiento.get("estado");
                historial.add(historial(historial.size() + 1, (Integer) movimiento.get("idUsuario"), id, paso, estado,
                        (String) movimiento.get("observaciones"), haceMinutos(now, movimiento.get("minutosAtras"))));
            }

            Map<String, Object> demanda = new TreeMap<>();
            demanda.put("_id", id);
            demanda.put("idUsuario", idUsuario);
            demanda.put("caratula", caratula);
            demanda.put("idTipoDemanda", workflow.get("idTipoDemanda"));
            demanda.put("idTipologia", workflow.get("idTipologia"));
            demanda.put("idSubtipologia", workflow.get("idSubtipologia"));
            demanda.put("domicilio", row.get("domicilio"));
            demanda.put("rutaImagen", null);
            demanda.put("fechaCreacion", fechaCreacion);
            demanda.put("informacionAdicional", row.get("informacionAdicional"));
            demanda.put("paso", paso);
            demanda.put("urlBpmn", urlBpmn);
            demanda.put("idsArea", BpmnAreas.parse(texto("seed/bpmn/" + workflow.get("bpmnBlob"))));
            demanda.put("estado", estado);
            demandas.add(demanda);
        }

        mongoTemplate.remove(Query.query(Criteria.where("_id").not().regex("^" + CounterService.ID_PREFIX)), COUNTERS);
        insert("demanda", "com.gestionexpedientes.demanda.entity.DemandaEntity", demandas);
        insert("historial_demanda", "com.gestionexpedientes.historial_demanda.entity.HistorialDemandaEntity", historial);
        secuencias.forEach((key, seq) -> mongoTemplate.getCollection(COUNTERS).insertOne(new Document("_id", key).append("seq", seq)));
        logger.info("  counters: {} secuencias de caratula", secuencias.size());
    }

    private String caratula(Map<String, Object> workflow, Date fecha, Map<String, Long> secuencias) {
        String codigoTipologia = String.format("%03d", (Integer) workflow.get("idTipologia"));
        String tipoDemanda = TipoDemanda.fromId((Integer) workflow.get("idTipoDemanda"))
                .map(TipoDemanda::getCodigo)
                .orElseThrow(() -> new IllegalStateException("Tipo de demanda inexistente en workflow " + workflow.get("_id")));
        String anio = new SimpleDateFormat("yyyy").format(fecha);

        long secuencia = secuencias.merge(anio + "-" + codigoTipologia + "-" + tipoDemanda, 1L, Long::sum);
        return codigoTipologia + "-" + tipoDemanda + "-" + anio + "-" + String.format("%05d", secuencia);
    }

    private static Map<String, Object> historial(int id, int idUsuario, int idDemanda, String paso, int estado, String observaciones, Date fecha) {
        Map<String, Object> row = new TreeMap<>();
        row.put("_id", id);
        row.put("idUsuario", idUsuario);
        row.put("idDemanda", idDemanda);
        row.put("paso", paso);
        row.put("estado", estado);
        row.put("observaciones", observaciones);
        row.put("fecha", fecha);
        return row;
    }

    private static Date haceMinutos(long now, Object minutos) {
        return new Date(now - TimeUnit.MINUTES.toMillis(((Number) minutos).longValue()));
    }

    private BlobContainerClient container(String name) {
        BlobContainerClient container = blobStorage.container(name);
        if (!container.exists())
            container.create();
        return container;
    }

    private String upload(BlobContainerClient container, String blobName, String resource) throws Exception {
        BlobClient blob = container.getBlobClient(blobName);
        ClassPathResource file = new ClassPathResource(resource);
        try (InputStream in = file.getInputStream()) {
            blob.upload(in, file.contentLength(), true);
        }
        return blobStorage.publicUrl(blob);
    }

    private void createIndexes() {
        unique("area", "nombre");
        unique("tipologia", "nombre");
        unique("subtipologia", "nombre");
        unique("workflow", "nombre");
        mongoTemplate.indexOps("workflow").createIndex(new Index()
                .on("idTipoDemanda", Sort.Direction.ASC).on("idTipologia", Sort.Direction.ASC)
                .on("idSubtipologia", Sort.Direction.ASC).unique());
        unique("users", "email");
        unique("users", "dni");
        unique("demanda", "caratula");
        mongoTemplate.indexOps("demanda").createIndex(new Index().on("idUsuario", Sort.Direction.ASC));
        mongoTemplate.indexOps("demanda").createIndex(new Index().on("estado", Sort.Direction.ASC));
        mongoTemplate.indexOps("demanda").createIndex(new Index().on("fechaCreacion", Sort.Direction.DESC));
        mongoTemplate.indexOps("demanda").createIndex(new Index().on("idsArea", Sort.Direction.ASC));
        mongoTemplate.indexOps("historial_demanda").createIndex(new Index().on("idDemanda", Sort.Direction.ASC));
        logger.info("  indices creados");
    }

    private void unique(String collection, String field) {
        mongoTemplate.indexOps(collection).createIndex(new Index().on(field, Sort.Direction.ASC).unique());
    }

    private String texto(String resource) throws Exception {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<Map<String, Object>> load(String name) throws Exception {
        try (InputStream in = new ClassPathResource("seed/" + name + ".json").getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<List<Map<String, Object>>>() {});
        }
    }

    @FunctionalInterface
    private interface Rows {
        List<Map<String, Object>> get() throws Exception;
    }
}
