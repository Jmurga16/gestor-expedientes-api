package com.gestionexpedientes.seed;

import com.gestionexpedientes.demanda.entity.DemandaEntity;
import com.gestionexpedientes.demanda.service.BpmnAreas;
import com.gestionexpedientes.demanda.service.BpmnSteps;
import com.gestionexpedientes.file.service.FileService;
import com.gestionexpedientes.global.exceptions.AttributeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("backfill")
public class BackfillRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(BackfillRunner.class);

    private final MongoTemplate mongoTemplate;
    private final FileService fileService;
    private final ConfigurableApplicationContext context;

    public BackfillRunner(MongoTemplate mongoTemplate, FileService fileService, ConfigurableApplicationContext context) {
        this.mongoTemplate = mongoTemplate;
        this.fileService = fileService;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        List<DemandaEntity> pendientes = mongoTemplate.find(
                Query.query(Criteria.where("idsArea").exists(false)), DemandaEntity.class);
        logger.info("Backfill de idsArea sobre base '{}': {} expedientes", mongoTemplate.getDb().getName(), pendientes.size());

        int fallidos = 0;
        for (DemandaEntity demanda : pendientes) {
            try {
                List<Integer> idsArea = BpmnAreas.parse(fileService.readBlobUrl(demanda.getUrlBpmn()));
                mongoTemplate.updateFirst(
                        Query.query(Criteria.where("_id").is(demanda.getId())),
                        new Update().set("idsArea", idsArea),
                        DemandaEntity.class);
                logger.info("  {} -> {}", demanda.getCaratula(), idsArea);
            } catch (RuntimeException | AttributeException e) {
                fallidos++;
                logger.warn("  {}: no se pudo leer {} ({})", demanda.getCaratula(), demanda.getUrlBpmn(), e.getMessage());
            }
        }

        mongoTemplate.indexOps("demanda").createIndex(new Index().on("idsArea", Sort.Direction.ASC));
        fallidos += completarPasos();
        logger.info("Backfill finalizado ({} sin resolver).", fallidos);

        int salida = fallidos == 0 ? 0 : 1;
        System.exit(SpringApplication.exit(context, () -> salida));
    }

    private int completarPasos() {
        List<DemandaEntity> pendientes = mongoTemplate.find(Query.query(Criteria.where("idAreaPaso").exists(false)
                .and("paso").ne("Finalizado").and("estado").ne(0)), DemandaEntity.class);
        logger.info("Backfill de idPaso e idAreaPaso: {} expedientes", pendientes.size());

        int fallidos = 0;
        for (DemandaEntity demanda : pendientes) {
            try {
                BpmnSteps.Pasos pasos = BpmnSteps.leer(fileService.readBlobUrl(demanda.getUrlBpmn()));
                List<String> ids = pasos.idsDe(demanda.getPaso());
                String idPaso = ids.size() == 1 ? ids.get(0) : null;
                Integer idArea = BpmnSteps.PASO_INICIAL.equals(demanda.getPaso()) ? pasos.areaInicial()
                        : idPaso != null ? pasos.areaDeTarea(idPaso) : null;

                Update update = new Update();
                if (idPaso != null)
                    update.set("idPaso", idPaso);
                if (idArea != null)
                    update.set("idAreaPaso", idArea);
                if (!update.getUpdateObject().isEmpty())
                    mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(demanda.getId())), update, DemandaEntity.class);
                logger.info("  {} -> paso {} / area {}", demanda.getCaratula(), idPaso, idArea);
            } catch (RuntimeException | AttributeException e) {
                fallidos++;
                logger.warn("  {}: no se pudo leer {} ({})", demanda.getCaratula(), demanda.getUrlBpmn(), e.getMessage());
            }
        }
        return fallidos;
    }
}
