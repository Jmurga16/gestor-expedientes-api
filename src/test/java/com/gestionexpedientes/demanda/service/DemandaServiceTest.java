package com.gestionexpedientes.demanda.service;

import com.gestionexpedientes.counter.service.CounterService;
import com.gestionexpedientes.demanda.dto.DemandaRequestDto;
import com.gestionexpedientes.demanda.dto.MovimientoDto;
import com.gestionexpedientes.demanda.dto.ObservacionDto;
import com.gestionexpedientes.demanda.entity.DemandaEntity;
import com.gestionexpedientes.demanda.repository.IDemandaRepository;
import com.gestionexpedientes.file.service.FileService;
import com.gestionexpedientes.global.dto.BpmnDto;
import com.gestionexpedientes.global.exceptions.ConflictException;
import com.gestionexpedientes.historial_demanda.entity.RegistroHistorial;
import com.gestionexpedientes.security.service.UserPrincipal;
import com.gestionexpedientes.subtipologia.repository.ISubTipologiaRepository;
import com.gestionexpedientes.tipologia.repository.ITipologiaRepository;
import com.gestionexpedientes.user.repository.IUserRepository;
import com.gestionexpedientes.workflow.repository.IWorkflowRepository;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemandaServiceTest {

    private static final String BPMN_WORKFLOW = "https://cuenta.blob.core.windows.net/workflow-bpmn/workflow-01.bpmn";
    private static final String XML_DOS_AREAS =
            "<bpmn:definitions><bpmn:laneSet id=\"LaneSet_1\">"
                    + "<bpmn:lane id=\"Lane_Vecino\" name=\"Vecino\" />"
                    + "<bpmn:lane id=\"Lane_4\" name=\"Servicios Publicos\" />"
                    + "<bpmn:lane id=\"Lane_5\" name=\"Obras Publicas\" />"
                    + "</bpmn:laneSet></bpmn:definitions>";
    private static final String XML_CIRCUITO = """
            <b:definitions xmlns:b="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <b:process>
                <b:laneSet>
                  <b:lane id="Lane_Vecino"><b:flowNodeRef>Start</b:flowNodeRef></b:lane>
                  <b:lane id="Lane_5"><b:flowNodeRef>T1</b:flowNodeRef></b:lane>
                  <b:lane id="Lane_4"><b:flowNodeRef>T2</b:flowNodeRef></b:lane>
                </b:laneSet>
                <b:startEvent id="Start" name="Inicio"/>
                <b:userTask id="T1" name="Inspección"/>
                <b:task id="T2" name="Limpieza"/>
                <b:sequenceFlow id="F1" sourceRef="Start" targetRef="T1"/>
                <b:sequenceFlow id="F2" sourceRef="T1" targetRef="T2"/>
              </b:process>
            </b:definitions>
            """;

    private static final String XML_REPETIDO = """
            <b:definitions xmlns:b="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <b:process>
                <b:laneSet>
                  <b:lane id="Lane_5"><b:flowNodeRef>R1</b:flowNodeRef></b:lane>
                  <b:lane id="Lane_4"><b:flowNodeRef>R2</b:flowNodeRef></b:lane>
                </b:laneSet>
                <b:task id="R1" name="Revisión"/>
                <b:task id="R2" name="Revisión"/>
              </b:process>
            </b:definitions>
            """;

    @Mock private IDemandaRepository demandaRepository;
    @Mock private ITipologiaRepository tipologiaRepository;
    @Mock private ISubTipologiaRepository subtipologiaRepository;
    @Mock private IUserRepository userRepository;
    @Mock private IWorkflowRepository workflowRepository;
    @Mock private FileService fileService;
    @Mock private CounterService counterService;
    @Mock private MongoTemplate mongoTemplate;

    private DemandaService demandaService;

    @BeforeEach
    void setUp() {
        demandaService = new DemandaService(demandaRepository, tipologiaRepository, subtipologiaRepository, userRepository,
                workflowRepository, fileService, new DemandaAccessService(), counterService, mongoTemplate);
    }

    @Test
    @DisplayName("La caratula sale con formato NNN-TIPO-AAAA-SSSSS")
    void caratulaConFormatoCompleto() throws Exception {
        DemandaEntity guardada = guardar(demanda(1, 7, 20), 42L);

        String anio = new SimpleDateFormat("yyyy").format(new Date());
        assertThat(guardada.getCaratula()).isEqualTo("007-PT-" + anio + "-00042");
        assertThat(guardada.getCaratula()).matches("\\d{3}-[A-Z]{2}-\\d{4}-\\d{5}");
    }

    @Test
    @DisplayName("La secuencia avanza por terna anio-tipologia-tipo de demanda")
    void secuenciaPorTerna() throws Exception {
        guardar(demanda(4, 3, 25), 1L);

        String anio = new SimpleDateFormat("yyyy").format(new Date());
        verify(counterService).next(anio + "-003-RC");
    }

    @Test
    @DisplayName("La demanda guarda las areas del flujo para la bandeja de referentes")
    void guardaLasAreasDelFlujo() throws Exception {
        DemandaEntity guardada = guardar(demanda(1, 7, 20), 1L);

        assertThat(guardada.getIdsArea()).containsExactly(4, 5);
        assertThat(guardada.getVersion()).isZero();
        assertThat(guardada.getHistorial()).singleElement()
                .extracting(RegistroHistorial::paso, RegistroHistorial::estado).containsExactly("Inicio", 1);
    }

    @Test
    @DisplayName("Inicio queda a cargo del area de la primera tarea")
    void inicioACargoDeLaPrimeraTarea() throws Exception {
        DemandaEntity guardada = guardar(demanda(1, 7, 20), 1L, XML_CIRCUITO);

        assertThat(guardada.getIdAreaPaso()).isEqualTo(5);
    }

    @Test
    @DisplayName("Un tipo de demanda inexistente no genera caratula")
    void tipoDemandaInexistente() {
        when(workflowRepository.findBpmnByIdTipoDemandaAndIdTipologiaAndIdSubtipologia(99, 7, 20))
                .thenReturn(Optional.of(new BpmnDto(BPMN_WORKFLOW)));

        assertThatThrownBy(() -> demandaService.save(demanda(99, 7, 20), usuario()))
                .hasMessage("El tipo de demanda no existe.");
    }

    @Test
    @DisplayName("Mover a otra tarea registra el motivo y pasa la responsabilidad a su area")
    void moverValidaElPasoYCambiaElAreaResponsable() throws Exception {
        DemandaEntity entity = abierta();
        when(demandaRepository.findById(9)).thenReturn(Optional.of(entity));
        when(fileService.readBlobUrl(entity.getUrlBpmn())).thenReturn(XML_CIRCUITO);

        assertThatThrownBy(() -> demandaService.mover(9, movimiento("Inventado", 3, "Motivo QA"), admin()))
                .hasMessageContaining("no pertenece");
        assertThat(entity.getPaso()).isEqualTo("Inicio");

        aceptarGuardado();
        DemandaEntity movida = demandaService.mover(9, movimiento("Limpieza", 3, "Motivo QA"), admin());

        assertThat(movida.getPaso()).isEqualTo("Limpieza");
        assertThat(movida.getIdAreaPaso()).isEqualTo(4);
        assertThat(movida.getVersion()).isEqualTo(1);
        assertThat(registroGuardado()).extracting(RegistroHistorial::idUsuario, RegistroHistorial::paso, RegistroHistorial::idPaso, RegistroHistorial::observaciones)
                .containsExactly(1, "Limpieza", "T2", "Motivo QA");
    }

    @Test
    @DisplayName("El referente solo mueve cuando su area es responsable del paso actual")
    void referenteSoloMueveDesdeSuPaso() {
        DemandaAccessService access = new DemandaAccessService();
        DemandaEntity entity = abierta();
        entity.setIdAreaPaso(5);

        assertThat(access.canAdvance(entity, referente(5))).isTrue();
        assertThat(access.canAdvance(entity, referente(4))).isFalse();
        assertThat(access.canAdvance(entity, referente(99))).isFalse();

        entity.setIdAreaPaso(null);
        assertThat(access.canAdvance(entity, referente(4))).isTrue();
        assertThat(access.canAdvance(entity, referente(99))).isFalse();
    }

    @Test
    void referenteDeOtraAreaRecibe403AlMover() {
        DemandaEntity entity = abierta();
        entity.setIdAreaPaso(5);
        when(demandaRepository.findById(9)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> demandaService.mover(9, movimiento("Limpieza", 3, "Motivo"), referente(4)))
                .isInstanceOf(AccessDeniedException.class);
        sinGuardar();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_COLAB", "ROLE_USER"})
    void rolesDeConsultaNoMueven(String role) {
        DemandaEntity entity = abierta();
        when(demandaRepository.findById(9)).thenReturn(Optional.of(entity));
        UserPrincipal user = new UserPrincipal(3, 5, "qa", "qa", "x", List.of(new SimpleGrantedAuthority(role)));

        assertThatThrownBy(() -> demandaService.mover(9, movimiento("Inicio", 3, null), user))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(entity.getEstado()).isEqualTo(1);
    }

    @Test
    void cambioDePasoYCierreExigenMotivo() {
        when(demandaRepository.findById(9)).thenReturn(Optional.of(abierta()));

        assertThatThrownBy(() -> demandaService.mover(9, movimiento("Inspección", 3, " "), admin()))
                .hasMessageContaining("motivo del cambio de paso");
        assertThatThrownBy(() -> demandaService.mover(9, movimiento("Inicio", 4, null), admin()))
                .hasMessageContaining("motivo del cierre");
        assertThatThrownBy(() -> demandaService.mover(9, movimiento("Finalizado", 3, "Motivo"), admin()))
                .hasMessageContaining("requiere un estado de cierre");
        sinGuardar();
    }

    @Test
    void cambioDeEstadoSinCambiarPasoNoExigeMotivo() throws Exception {
        DemandaEntity entity = abierta();
        when(demandaRepository.findById(9)).thenReturn(Optional.of(entity));
        aceptarGuardado();

        assertThat(demandaService.mover(9, movimiento("Inicio", 6, null), admin()).getEstado()).isEqualTo(6);
        assertThat(registroGuardado().observaciones()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 8, 99})
    void estadoFueraDeCatalogoNoSeGuarda(int estado) {
        when(demandaRepository.findById(9)).thenReturn(Optional.of(abierta()));

        assertThatThrownBy(() -> demandaService.mover(9, movimiento("Inicio", estado, "Motivo"), admin()))
                .hasMessageContaining("entre 1 y 6");
    }

    @Test
    void finalizadoYaNoSeUsaParaCerrar() {
        when(demandaRepository.findById(9)).thenReturn(Optional.of(abierta()));

        assertThatThrownBy(() -> demandaService.mover(9, movimiento("Finalizado", 7, "Motivo"), admin()))
                .hasMessageContaining("Finalizado ya no se usa");
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 5, 7})
    void cerradoNoSeMueveNiSeEliminaSalvoReaperturaDelAdmin(int estado) {
        DemandaEntity entity = cerrada(estado);
        when(demandaRepository.findById(9)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> demandaService.mover(9, movimiento("Inspección", 3, "Motivo"), referente(5)))
                .hasMessageContaining("Solo un administrador puede reabrirlo");
        assertThatThrownBy(() -> demandaService.delete(9, admin())).hasMessageContaining("no puede eliminarse");
        sinGuardar();
    }

    @Test
    void reaperturaExigeMotivoYUnEstadoAbierto() throws Exception {
        DemandaEntity entity = cerrada(4);
        when(demandaRepository.findById(9)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> demandaService.mover(9, movimiento("Inspección", 3, null), admin()))
                .hasMessageContaining("motivo de la reapertura");
        assertThatThrownBy(() -> demandaService.mover(9, movimiento("Inspección", 5, "Error"), admin()))
                .hasMessageContaining("Para reabrir");
        assertThatThrownBy(() -> demandaService.mover(9, movimiento("Finalizado", 3, "Error"), admin()))
                .hasMessageContaining("Para reabrir");

        when(fileService.readBlobUrl(entity.getUrlBpmn())).thenReturn(XML_CIRCUITO);
        aceptarGuardado();
        DemandaEntity reabierta = demandaService.mover(9, movimiento("Inspección", 3, "Se cerró por error"), admin());

        assertThat(reabierta.getEstado()).isEqualTo(3);
        assertThat(reabierta.getIdAreaPaso()).isEqualTo(5);
        assertThat(registroGuardado().observaciones()).isEqualTo("Reapertura: Se cerró por error");
    }

    @Test
    @DisplayName("Si otro usuario guardó antes, responde conflicto y no registra historial")
    void versionDesactualizadaDevuelveConflicto() {
        DemandaEntity entity = abierta();
        entity.setVersion(3L);
        when(demandaRepository.findById(9)).thenReturn(Optional.of(entity));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(DemandaEntity.class))).thenReturn(UpdateResult.acknowledged(0, 0L, null));

        DemandaRequestDto dto = demanda(1, 7, 20);
        dto.setVersion(2L);
        assertThatThrownBy(() -> demandaService.update(9, dto, admin())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> demandaService.mover(9, new MovimientoDto("Inicio", null, 6, null, 2L), admin()))
                .isInstanceOf(ConflictException.class);
        assertThat(entity.getVersion()).isEqualTo(3);
    }

    @Test
    void guardadoAtomicoComparaLaVersionLeida() throws Exception {
        DemandaEntity entity = abierta();
        when(demandaRepository.findById(9)).thenReturn(Optional.of(entity));
        aceptarGuardado();
        DemandaRequestDto dto = demanda(1, 7, 20);
        dto.setVersion(4L);

        demandaService.update(9, dto, admin());

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).updateFirst(query.capture(), any(Update.class), eq(DemandaEntity.class));
        assertThat(query.getValue().getQueryObject().toJson()).contains("\"version\": 4");
        assertThat(entity.getVersion()).isEqualTo(5);
    }

    @Test
    void editarDatosNoTocaPasoNiEstadoYExigeVersion() throws Exception {
        DemandaEntity entity = cerrada(4);
        when(demandaRepository.findById(9)).thenReturn(Optional.of(entity));
        DemandaRequestDto dto = demanda(1, 7, 20);
        dto.setDomicilio("Calle Nueva 456");

        assertThatThrownBy(() -> demandaService.update(9, dto, admin())).hasMessageContaining("versión");

        aceptarGuardado();
        dto.setVersion(0L);
        DemandaEntity actualizada = demandaService.update(9, dto, admin());

        assertThat(actualizada.getDomicilio()).isEqualTo("Calle Nueva 456");
        assertThat(actualizada.getPaso()).isEqualTo("Finalizado");
        assertThat(actualizada.getEstado()).isEqualTo(4);
    }

    @Test
    void cambiarClasificacionNoModificaDatosNiHistorial() {
        DemandaEntity entity = abierta();
        when(demandaRepository.findById(9)).thenReturn(Optional.of(entity));
        DemandaRequestDto dto = demanda(1, 8, 20);
        dto.setDomicilio("No guardar");
        dto.setVersion(0L);

        assertThatThrownBy(() -> demandaService.update(9, dto, admin())).hasMessageContaining("clasificación");
        assertThat(entity.getDomicilio()).isEqualTo("Calle Falsa 123");
        sinGuardar();
    }

    @Test
    @DisplayName("El vecino edita o elimina su expediente solo mientras no fue tomado")
    void vecinoSoloAntesDeSerTomado() {
        DemandaAccessService access = new DemandaAccessService();
        DemandaEntity entity = abierta();

        assertThat(access.canEdit(entity, usuario())).isTrue();
        assertThat(access.canDelete(entity, usuario())).isTrue();

        entity.setPaso("Inspección");
        assertThat(access.canEdit(entity, usuario())).isFalse();
        assertThat(access.canDelete(entity, usuario())).isFalse();
        assertThat(access.canDelete(entity, referente(5))).isFalse();
        assertThat(access.canDelete(entity, admin())).isTrue();
    }

    @Test
    void colaboradorAgregaObservacionesPeroElVecinoNo() throws Exception {
        DemandaEntity entity = abierta();
        when(demandaRepository.findById(9)).thenReturn(Optional.of(entity));
        UserPrincipal colaborador = new UserPrincipal(4, 5, "colab", "colab", "x", List.of(new SimpleGrantedAuthority("ROLE_COLAB")));

        aceptarGuardado();
        demandaService.observar(9, new ObservacionDto("  Llamé al vecino  "), colaborador);
        assertThat(registroGuardado()).extracting(RegistroHistorial::idUsuario, RegistroHistorial::observaciones)
                .containsExactly(4, "Llamé al vecino");
        assertThat(updateGuardado().getUpdateObject()).doesNotContainKey("$set");

        assertThatThrownBy(() -> demandaService.observar(9, new ObservacionDto("Hola"), usuario()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Con nombres repetidos el paso se identifica por el ID de la tarea")
    void pasoIdentificadoPorId() throws Exception {
        DemandaEntity entity = abierta();
        when(demandaRepository.findById(9)).thenReturn(Optional.of(entity));
        when(fileService.readBlobUrl(entity.getUrlBpmn())).thenReturn(XML_REPETIDO);

        assertThatThrownBy(() -> demandaService.mover(9, movimiento("Revisión", 3, "Motivo"), admin()))
                .hasMessageContaining("varias tareas");

        aceptarGuardado();
        DemandaEntity movida = demandaService.mover(9, new MovimientoDto("Revisión", "R2", 3, "Motivo", 0L), admin());

        assertThat(movida.getIdPaso()).isEqualTo("R2");
        assertThat(movida.getIdAreaPaso()).isEqualTo(4);
        assertThat(updateGuardado().getUpdateObject().get("$set", Document.class))
                .containsEntry("idPaso", "R2").containsEntry("idAreaPaso", 4).containsEntry("version", 1L);
    }

    @Test
    void idDeTareaInexistenteSeRechaza() throws Exception {
        DemandaEntity entity = abierta();
        when(demandaRepository.findById(9)).thenReturn(Optional.of(entity));
        when(fileService.readBlobUrl(entity.getUrlBpmn())).thenReturn(XML_REPETIDO);

        assertThatThrownBy(() -> demandaService.mover(9, new MovimientoDto("Revisión", "Inventado", 3, "Motivo", 0L), admin()))
                .hasMessageContaining("no pertenece");
        sinGuardar();
    }

    @Test
    void eliminarRegistraLaBajaEnElHistorial() throws Exception {
        when(demandaRepository.findById(9)).thenReturn(Optional.of(abierta()));
        aceptarGuardado();

        demandaService.delete(9, admin());

        assertThat(registroGuardado().observaciones()).isEqualTo("Expediente eliminado.");
        assertThat(updateGuardado().getUpdateObject().get("$set", Document.class)).containsEntry("estado", 0);
    }

    @Test
    void eliminadoNoPuedeLeerse() {
        DemandaEntity entity = abierta();
        entity.setEstado(0);
        when(demandaRepository.findById(9)).thenReturn(Optional.of(entity));
        assertThatThrownBy(() -> demandaService.getOne(9, admin())).hasMessage("Registro no encontrado.");
    }

    private DemandaEntity guardar(DemandaRequestDto dto, long secuencia) throws Exception {
        return guardar(dto, secuencia, XML_DOS_AREAS);
    }

    private DemandaEntity guardar(DemandaRequestDto dto, long secuencia, String xml) throws Exception {
        when(workflowRepository.findBpmnByIdTipoDemandaAndIdTipologiaAndIdSubtipologia(
                dto.getIdTipoDemanda(), dto.getIdTipologia(), dto.getIdSubtipologia()))
                .thenReturn(Optional.of(new BpmnDto(BPMN_WORKFLOW)));
        when(counterService.next(anyString())).thenReturn(secuencia);
        when(counterService.nextId("demanda")).thenReturn(9);
        when(fileService.copyFileWithNewName(anyString(), anyString(), anyString())).thenReturn("https://cuenta.blob.core.windows.net/demanda-bpmn/demanda9.bpmn");
        when(fileService.readBlobUrl(BPMN_WORKFLOW)).thenReturn(xml);
        when(demandaRepository.save(any(DemandaEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        demandaService.save(dto, usuario());

        ArgumentCaptor<DemandaEntity> captor = ArgumentCaptor.forClass(DemandaEntity.class);
        verify(demandaRepository).save(captor.capture());
        return captor.getValue();
    }

    private void aceptarGuardado() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(DemandaEntity.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
    }

    private void sinGuardar() {
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(DemandaEntity.class));
    }

    private Update updateGuardado() {
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), update.capture(), eq(DemandaEntity.class));
        return update.getValue();
    }

    private RegistroHistorial registroGuardado() {
        return (RegistroHistorial) updateGuardado().getUpdateObject().get("$push", Document.class).get("historial");
    }

    private static MovimientoDto movimiento(String paso, int estado, String observaciones) {
        return new MovimientoDto(paso, null, estado, observaciones, 0L);
    }

    private static DemandaEntity abierta() {
        return new DemandaEntity(9, 3, "007-PT-2026-00042", 1, 7, 20, "Calle Falsa 123", null,
                "Sin novedades", "Inicio", "https://cuenta.blob.core.windows.net/demanda-bpmn/demanda9.bpmn",
                List.of(4, 5), new Date(), 1);
    }

    private static DemandaEntity cerrada(int estado) {
        DemandaEntity entity = abierta();
        entity.setPaso("Finalizado");
        entity.setEstado(estado);
        return entity;
    }

    private static DemandaRequestDto demanda(int idTipoDemanda, int idTipologia, int idSubtipologia) {
        DemandaRequestDto dto = new DemandaRequestDto();
        dto.setIdTipoDemanda(idTipoDemanda);
        dto.setIdTipologia(idTipologia);
        dto.setIdSubtipologia(idSubtipologia);
        dto.setDomicilio("Calle Falsa 123");
        return dto;
    }

    private static UserPrincipal usuario() {
        return new UserPrincipal(3, null, "vecino@demo.test", "vecino@demo.test", "x",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static UserPrincipal referente(int idArea) {
        return new UserPrincipal(5, idArea, "referente@demo.test", "referente@demo.test", "x",
                List.of(new SimpleGrantedAuthority("ROLE_AREA")));
    }

    private static UserPrincipal admin() {
        return new UserPrincipal(1, null, "admin@demo.test", "admin@demo.test", "x",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
