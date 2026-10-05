package com.gestionexpedientes.demanda.service;

import com.gestionexpedientes.demanda.dto.DemandaRequestDto;
import com.gestionexpedientes.demanda.dto.DemandaListDto;
import com.gestionexpedientes.demanda.dto.MovimientoDto;
import com.gestionexpedientes.demanda.dto.ObservacionDto;
import com.gestionexpedientes.demanda.dto.PermisosDemandaDto;
import com.gestionexpedientes.counter.service.CounterService;
import com.gestionexpedientes.file.service.FileService;
import com.gestionexpedientes.global.dto.BpmnDto;
import com.gestionexpedientes.global.dto.PageDto;
import com.gestionexpedientes.demanda.entity.DemandaEntity;
import com.gestionexpedientes.demanda.repository.IDemandaRepository;
import com.gestionexpedientes.global.exceptions.AttributeException;
import com.gestionexpedientes.global.exceptions.ConflictException;
import com.gestionexpedientes.global.exceptions.WorkflowNotConfiguredException;
import com.gestionexpedientes.global.exceptions.ResourceNotFoundException;
import com.gestionexpedientes.historial_demanda.entity.RegistroHistorial;
import com.gestionexpedientes.security.service.UserPrincipal;
import com.gestionexpedientes.subtipologia.entity.SubTipologiaEntity;
import com.gestionexpedientes.subtipologia.repository.ISubTipologiaRepository;
import com.gestionexpedientes.tipodemanda.TipoDemanda;
import com.gestionexpedientes.tipologia.entity.TipologiaEntity;
import com.gestionexpedientes.tipologia.repository.ITipologiaRepository;
import com.gestionexpedientes.user.entity.UserEntity;
import com.gestionexpedientes.user.repository.IUserRepository;
import com.gestionexpedientes.workflow.repository.IWorkflowRepository;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DemandaService {

    private static final String PASO_FINAL = "Finalizado";
    private static final int ESTADO_ELIMINADA = 0;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_EXPORT_SIZE = 5000;
    private static final Sort ORDEN_BANDEJA = Sort.by(Sort.Direction.DESC, "fechaCreacion").and(Sort.by(Sort.Direction.DESC, "_id"));
    private static final String WORKFLOW_NO_CONFIGURADO =
            "No hay un flujo de trabajo definido para esa combinación de Tipo de Demanda, Tipología y Subtipología.";
    private static final String DEMANDA_CERRADA =
            "El expediente está cerrado. Solo un administrador puede reabrirlo.";
    private static final String SIN_VERSION =
            "Falta la versión del expediente; vuelva a cargarlo antes de guardar.";
    private static final String CONFLICTO =
            "Otro usuario modificó este expediente mientras usted lo tenía abierto. Sus cambios no se guardaron: recargue para ver la versión actual.";

    private final IDemandaRepository demandaRepository;
    private final ITipologiaRepository tipologiaRepository;
    private final ISubTipologiaRepository subtipologiaRepository;
    private final IUserRepository userRepository;
    private final IWorkflowRepository workflowRepository;
    private final FileService fileService;
    private final DemandaAccessService demandaAccessService;
    private final CounterService counterService;
    private final MongoTemplate mongoTemplate;

    public DemandaService(IDemandaRepository demandaRepository,
                          ITipologiaRepository tipologiaRepository,
                          ISubTipologiaRepository subtipologiaRepository,
                          IUserRepository userRepository,
                          IWorkflowRepository workflowRepository,
                          FileService fileService,
                          DemandaAccessService demandaAccessService,
                          CounterService counterService,
                          MongoTemplate mongoTemplate) {
        this.demandaRepository = demandaRepository;
        this.tipologiaRepository = tipologiaRepository;
        this.subtipologiaRepository = subtipologiaRepository;
        this.userRepository = userRepository;
        this.workflowRepository = workflowRepository;
        this.fileService = fileService;
        this.demandaAccessService = demandaAccessService;
        this.counterService = counterService;
        this.mongoTemplate = mongoTemplate;
    }

    public PageDto<DemandaListDto> getDatatable(String search, int pageIndex, int pageSize, UserPrincipal user) {
        int page = Math.max(1, pageIndex);
        int size = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        Query query = Query.query(new Criteria().andOperator(alcance(user), busqueda(search)));
        query.fields().exclude("historial");
        long total = mongoTemplate.count(query, DemandaEntity.class);
        List<DemandaEntity> demandas =
                mongoTemplate.find(query.with(ORDEN_BANDEJA).skip((long) (page - 1) * size).limit(size), DemandaEntity.class);

        return new PageDto<>(toListDto(demandas, user), page, size, total);
    }

    public List<DemandaListDto> getExport(String search, UserPrincipal user) {
        Query query = Query.query(new Criteria().andOperator(alcance(user), busqueda(search)));
        query.fields().exclude("historial");
        return toListDto(mongoTemplate.find(query.with(ORDEN_BANDEJA).limit(MAX_EXPORT_SIZE), DemandaEntity.class), user);
    }

    public Map<Integer, Long> getResumen(UserPrincipal user) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(alcance(user)),
                Aggregation.group("estado").count().as("total"));

        return mongoTemplate.aggregate(aggregation, DemandaEntity.class, Document.class).getMappedResults().stream()
                .collect(Collectors.toMap(doc -> doc.getInteger("_id"), doc -> ((Number) doc.get("total")).longValue()));
    }

    private Criteria alcance(UserPrincipal user) {
        Criteria criteria = Criteria.where("estado").ne(ESTADO_ELIMINADA);
        if (user.isAdmin())
            return criteria;
        if (user.isAreaStaff())
            return criteria.orOperator(
                    Criteria.where("idsArea").is(user.getIdArea()),
                    Criteria.where("idUsuario").is(user.getId()));
        return criteria.and("idUsuario").is(user.getId());
    }

    private Criteria busqueda(String search) {
        if (search == null || search.isBlank())
            return new Criteria();

        Pattern pattern = Pattern.compile(Pattern.quote(search.trim()), Pattern.CASE_INSENSITIVE);
        List<Criteria> opciones = new ArrayList<>(List.of(
                Criteria.where("caratula").regex(pattern),
                Criteria.where("domicilio").regex(pattern),
                Criteria.where("informacionAdicional").regex(pattern),
                Criteria.where("paso").regex(pattern)));

        Query usuarios = Query.query(new Criteria().orOperator(
                Criteria.where("dni").regex(pattern),
                Criteria.where("name").regex(pattern),
                Criteria.where("lastname").regex(pattern)));
        usuarios.fields().include("_id");
        List<Integer> idsUsuario = mongoTemplate.find(usuarios, Document.class, "users").stream()
                .map(doc -> doc.getInteger("_id"))
                .collect(Collectors.toList());
        if (!idsUsuario.isEmpty())
            opciones.add(Criteria.where("idUsuario").in(idsUsuario));

        return new Criteria().orOperator(opciones.toArray(new Criteria[0]));
    }

    private List<DemandaListDto> toListDto(List<DemandaEntity> demandas, UserPrincipal user) {
        Map<Integer, UserEntity> usuarios = porId(userRepository.findAllById(ids(demandas, DemandaEntity::getIdUsuario)), UserEntity::getId);
        Map<Integer, TipologiaEntity> tipologias = porId(tipologiaRepository.findAllById(ids(demandas, DemandaEntity::getIdTipologia)), TipologiaEntity::getId);
        Map<Integer, SubTipologiaEntity> subtipologias = porId(subtipologiaRepository.findAllById(ids(demandas, DemandaEntity::getIdSubtipologia)), SubTipologiaEntity::getId);

        return demandas.stream()
                .map(demanda -> mapToListDto(demanda,
                        usuarios.get(demanda.getIdUsuario()),
                        tipologias.get(demanda.getIdTipologia()),
                        subtipologias.get(demanda.getIdSubtipologia()), user))
                .collect(Collectors.toList());
    }

    private static List<Integer> ids(List<DemandaEntity> demandas, Function<DemandaEntity, Integer> campo) {
        return demandas.stream().map(campo).distinct().collect(Collectors.toList());
    }

    private static <T> Map<Integer, T> porId(Iterable<T> entidades, Function<T, Integer> id) {
        Map<Integer, T> map = new HashMap<>();
        entidades.forEach(entidad -> map.put(id.apply(entidad), entidad));
        return map;
    }

    private DemandaListDto mapToListDto(DemandaEntity demanda, UserEntity usuario, TipologiaEntity tipologia,
                                        SubTipologiaEntity subtipologia, UserPrincipal user) {
        DemandaListDto dto = new DemandaListDto();

        dto.setId(demanda.getId());
        dto.setCaratula(demanda.getCaratula());
        dto.setInformacionAdicional(demanda.getInformacionAdicional());
        dto.setPaso(demanda.getPaso());
        dto.setEstado(demanda.getEstado());
        dto.setPuedeEditar(demandaAccessService.canEdit(demanda, user));
        dto.setPuedeEliminar(demandaAccessService.canDelete(demanda, user));

        if (usuario != null) {
            dto.setDemandante(usuario.getName() + " " + usuario.getLastname());
            dto.setDni(usuario.getDni());
        }
        if (tipologia != null) {
            dto.setDescripcion(tipologia.getDescripcion());
            dto.setTipologia(tipologia.getNombre());
        }
        if (subtipologia != null)
            dto.setSubtipologia(subtipologia.getNombre());

        dto.setTipoDemanda(TipoDemanda.fromId(demanda.getIdTipoDemanda()).map(TipoDemanda::getCodigo).orElse(null));
        return dto;
    }

    public DemandaEntity getOne(int id, UserPrincipal user) throws ResourceNotFoundException {

        DemandaEntity demanda = demandaRepository.findById(id)
                .filter(item -> item.getEstado() != ESTADO_ELIMINADA)
                .orElseThrow(() -> new ResourceNotFoundException("Registro no encontrado."));

        demandaAccessService.checkAccess(demanda, user);
        return demanda;
    }

    public List<DemandaEntity> getActives() {

        List<DemandaEntity> actives = demandaRepository.findByEstado(1);

        return actives;
    }

    public PermisosDemandaDto getPermisos(int id, UserPrincipal user) throws ResourceNotFoundException {
        return demandaAccessService.permisos(getOne(id, user), user);
    }

    public DemandaEntity save(DemandaRequestDto dto, UserPrincipal user) throws Exception {
        DemandaEntity demanda = mapTipologiaFromDto(dto, user);
        demanda.setHistorial(new ArrayList<>(List.of(registro(demanda, user, null))));
        return demandaRepository.save(demanda);
    }

    public DemandaEntity update(int id, DemandaRequestDto dto, UserPrincipal user) throws ResourceNotFoundException, AttributeException {
        DemandaEntity demanda = getOne(id, user);

        if (!demandaAccessService.canEdit(demanda, user))
            throw new AccessDeniedException("No tiene permiso para modificar los datos del expediente.");
        if (!Objects.equals(demanda.getIdTipoDemanda(), dto.getIdTipoDemanda())
                || !Objects.equals(demanda.getIdTipologia(), dto.getIdTipologia())
                || !Objects.equals(demanda.getIdSubtipologia(), dto.getIdSubtipologia()))
            throw new AttributeException("La clasificación del expediente no puede cambiar porque define su circuito BPMN.");
        if (dto.getVersion() == null)
            throw new AttributeException(SIN_VERSION);

        demanda.setDomicilio(dto.getDomicilio());
        demanda.setRutaImagen(dto.getRutaImagen());
        demanda.setInformacionAdicional(dto.getInformacionAdicional());

        Update cambios = new Update()
                .set("domicilio", demanda.getDomicilio())
                .set("rutaImagen", demanda.getRutaImagen())
                .set("informacionAdicional", demanda.getInformacionAdicional());
        return guardar(demanda, dto.getVersion(), cambios, registro(demanda, user, "Datos del expediente actualizados."));
    }

    public DemandaEntity mover(int id, MovimientoDto dto, UserPrincipal user) throws ResourceNotFoundException, AttributeException {
        DemandaEntity demanda = getOne(id, user);
        boolean reapertura = DemandaAccessService.esTerminal(demanda.getEstado());

        if (reapertura && !demandaAccessService.canReopen(demanda, user))
            throw new AttributeException(DEMANDA_CERRADA);
        if (!reapertura && !demandaAccessService.canAdvance(demanda, user))
            throw new AccessDeniedException("No tiene permiso para cambiar paso ni estado.");

        int estado = dto.estado();
        if (estado == DemandaAccessService.ESTADO_FINALIZADO)
            throw new AttributeException("El estado Finalizado ya no se usa: elija Cerrado y Resuelto o Cerrado sin Resolución.");
        if (estado < 1 || estado > 6)
            throw new AttributeException("El estado debe estar entre 1 y 6.");

        boolean cambiaPaso = !mismoPaso(demanda, dto);
        boolean cierra = DemandaAccessService.esTerminal(estado);
        String motivo = dto.observaciones() == null ? "" : dto.observaciones().trim();

        if (!cambiaPaso && estado == demanda.getEstado())
            throw new AttributeException("No hay cambios de paso ni de estado.");
        if (reapertura && (cierra || PASO_FINAL.equals(dto.paso())))
            throw new AttributeException("Para reabrir elija un paso del circuito y un estado abierto.");
        if (PASO_FINAL.equals(dto.paso()) && !cierra)
            throw new AttributeException("El paso Finalizado requiere un estado de cierre.");
        if (motivo.isEmpty() && (reapertura || cierra || cambiaPaso))
            throw new AttributeException(reapertura ? "Indique el motivo de la reapertura."
                    : cierra ? "Indique el motivo del cierre en Observaciones."
                    : "Indique el motivo del cambio de paso en Observaciones.");

        if (cambiaPaso) {
            Destino destino = destino(demanda, dto);
            demanda.setPaso(destino.paso());
            demanda.setIdPaso(destino.idPaso());
            demanda.setIdAreaPaso(destino.idArea());
        }
        demanda.setEstado(estado);

        Update cambios = new Update()
                .set("paso", demanda.getPaso())
                .set("idPaso", demanda.getIdPaso())
                .set("idAreaPaso", demanda.getIdAreaPaso())
                .set("estado", estado);
        String observaciones = reapertura ? "Reapertura: " + motivo : motivo.isEmpty() ? null : motivo;
        return guardar(demanda, dto.version(), cambios, registro(demanda, user, observaciones));
    }

    public void observar(int id, ObservacionDto dto, UserPrincipal user) throws ResourceNotFoundException {
        DemandaEntity demanda = getOne(id, user);
        if (!demandaAccessService.canObserve(demanda, user))
            throw new AccessDeniedException("No tiene permiso para agregar observaciones.");

        UpdateResult resultado = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(id).and("estado").ne(ESTADO_ELIMINADA)),
                new Update().push("historial", registro(demanda, user, dto.observaciones().trim())),
                DemandaEntity.class);
        if (resultado.getMatchedCount() == 0)
            throw new ResourceNotFoundException("Registro no encontrado.");
    }

    private static boolean mismoPaso(DemandaEntity demanda, MovimientoDto dto) {
        String idPaso = dto.idPaso() == null || dto.idPaso().isBlank() ? null : dto.idPaso();
        return Objects.equals(demanda.getPaso(), dto.paso())
                && (idPaso == null || demanda.getIdPaso() == null || idPaso.equals(demanda.getIdPaso()));
    }

    private Destino destino(DemandaEntity demanda, MovimientoDto dto) throws AttributeException {
        if (PASO_FINAL.equals(dto.paso()))
            return new Destino(PASO_FINAL, null, null);

        BpmnSteps.Pasos pasos = BpmnSteps.leer(fileService.readBlobUrl(demanda.getUrlBpmn()));
        if (BpmnSteps.PASO_INICIAL.equals(dto.paso()))
            return new Destino(BpmnSteps.PASO_INICIAL, null, pasos.areaInicial());

        String idPaso = dto.idPaso();
        if (idPaso == null || idPaso.isBlank()) {
            List<String> ids = pasos.idsDe(dto.paso());
            if (ids.size() > 1)
                throw new AttributeException("Hay varias tareas llamadas «" + dto.paso() + "»: elija el paso desde el diagrama.");
            idPaso = ids.isEmpty() ? null : ids.get(0);
        }
        String nombre = idPaso == null ? null : pasos.nombrePorId().get(idPaso);
        if (nombre == null)
            throw new AttributeException("El paso no pertenece al circuito BPMN del expediente.");
        return new Destino(nombre, idPaso, pasos.areaDeTarea(idPaso));
    }

    private record Destino(String paso, String idPaso, Integer idArea) {
    }

    private static RegistroHistorial registro(DemandaEntity demanda, UserPrincipal user, String observaciones) {
        return new RegistroHistorial(user.getId(), demanda.getPaso(), demanda.getIdPaso(), demanda.getEstado(),
                observaciones, new Date());
    }

    private DemandaEntity guardar(DemandaEntity demanda, long versionLeida, Update cambios, RegistroHistorial registro) {
        Criteria version = versionLeida == 0
                ? new Criteria().orOperator(Criteria.where("version").is(0L), Criteria.where("version").exists(false))
                : Criteria.where("version").is(versionLeida);
        Query query = Query.query(new Criteria().andOperator(Criteria.where("_id").is(demanda.getId()), version));

        cambios.set("version", versionLeida + 1).push("historial", registro);
        if (mongoTemplate.updateFirst(query, cambios, DemandaEntity.class).getMatchedCount() == 0)
            throw new ConflictException(CONFLICTO);
        demanda.setVersion(versionLeida + 1);
        return demanda;
    }

    public DemandaEntity delete(int id, UserPrincipal user) throws ResourceNotFoundException, AttributeException {
        DemandaEntity demanda = getOne(id, user);
        if (DemandaAccessService.esTerminal(demanda.getEstado()))
            throw new AttributeException("Un expediente cerrado o finalizado no puede eliminarse.");
        if (!demandaAccessService.canDelete(demanda, user))
            throw new AccessDeniedException("No tiene permiso para eliminar el expediente.");

        demanda.setEstado(ESTADO_ELIMINADA);
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("estado", ESTADO_ELIMINADA).inc("version", 1)
                        .push("historial", registro(demanda, user, "Expediente eliminado.")),
                DemandaEntity.class);
        return demanda;
    }

    private DemandaEntity mapTipologiaFromDto(DemandaRequestDto dto, UserPrincipal user) throws Exception {
        String urlBPMN = workflowRepository.findBpmnByIdTipoDemandaAndIdTipologiaAndIdSubtipologia(
                        dto.getIdTipoDemanda(), dto.getIdTipologia(), dto.getIdSubtipologia()
                ).map(BpmnDto::getBpmn)
                .orElseThrow(() -> new WorkflowNotConfiguredException(WORKFLOW_NO_CONFIGURADO));

        String caratula = setCaratula(dto);
        int id = counterService.nextId("demanda");
        Date fechaCreacion = new Date();

        String newNameBpmn = "demanda" + id + ".bpmn";
        String container = "demanda-bpmn";

        String bpmnDemanda = fileService.copyFileWithNewName(urlBPMN, container, newNameBpmn);
        String xml = fileService.readBlobUrl(urlBPMN);

        DemandaEntity demanda = new DemandaEntity(id, user.getId(), caratula, dto.getIdTipoDemanda(), dto.getIdTipologia(), dto.getIdSubtipologia(), dto.getDomicilio(), dto.getRutaImagen(), dto.getInformacionAdicional(), BpmnSteps.PASO_INICIAL, bpmnDemanda, BpmnAreas.parse(xml), fechaCreacion, DemandaAccessService.ESTADO_RECEPTADA);
        demanda.setIdAreaPaso(areaInicial(xml));
        demanda.setVersion(0L);
        return demanda;
    }

    private static Integer areaInicial(String xml) {
        try {
            return BpmnSteps.leer(xml).areaInicial();
        } catch (AttributeException e) {
            return null;
        }
    }

    private String setCaratula(DemandaRequestDto dto) throws AttributeException {
        String codigoTipologia = String.format("%03d", dto.getIdTipologia());

        String tipoDemanda = TipoDemanda.fromId(dto.getIdTipoDemanda())
                .map(TipoDemanda::getCodigo)
                .orElseThrow(() -> new AttributeException("El tipo de demanda no existe."));

        String anio = new SimpleDateFormat("yyyy").format(new Date());

        long secuencia = counterService.next(anio + "-" + codigoTipologia + "-" + tipoDemanda);

        return codigoTipologia + "-" + tipoDemanda + "-" + anio + "-" + String.format("%05d", secuencia);
    }
}
