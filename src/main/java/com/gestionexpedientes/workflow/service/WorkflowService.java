package com.gestionexpedientes.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gestionexpedientes.counter.service.CounterService;
import com.gestionexpedientes.global.exceptions.AttributeException;
import com.gestionexpedientes.global.service.AbstractCatalogService;
import com.gestionexpedientes.subtipologia.repository.ISubTipologiaRepository;
import com.gestionexpedientes.tipodemanda.TipoDemanda;
import com.gestionexpedientes.tipologia.repository.ITipologiaRepository;
import com.gestionexpedientes.workflow.dto.WorkflowDto;
import com.gestionexpedientes.workflow.dto.WorkflowListDto;
import com.gestionexpedientes.workflow.entity.WorkflowEntity;
import com.gestionexpedientes.workflow.repository.IWorkflowRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class WorkflowService extends AbstractCatalogService<WorkflowEntity, WorkflowDto> {

    private final IWorkflowRepository workflowRepository;
    private final ITipologiaRepository tipologiaRepository;
    private final ISubTipologiaRepository subtipologiaRepository;
    private final ObjectMapper objectMapper;

    public WorkflowService(IWorkflowRepository workflowRepository,
                           ITipologiaRepository tipologiaRepository,
                           ISubTipologiaRepository subtipologiaRepository,
                           ObjectMapper objectMapper,
                           CounterService counterService) {
        super(workflowRepository, counterService, "workflow");
        this.workflowRepository = workflowRepository;
        this.tipologiaRepository = tipologiaRepository;
        this.subtipologiaRepository = subtipologiaRepository;
        this.objectMapper = objectMapper;
    }

    public List<WorkflowListDto> getAllWithNames() {
        return workflowRepository.findAll().stream().map(this::mapToResponseDto).collect(Collectors.toList());
    }

    public boolean exists(int idTipoDemanda, int idTipologia, int idSubtipologia) {
        return workflowRepository.existsByIdTipoDemandaAndIdTipologiaAndIdSubtipologiaAndEstado(
                idTipoDemanda, idTipologia, idSubtipologia, ESTADO_ACTIVO);
    }

    @Override
    public WorkflowEntity save(WorkflowDto dto) throws AttributeException {
        if (workflowRepository.existsByIdTipoDemandaAndIdTipologiaAndIdSubtipologia(
                dto.getIdTipoDemanda(), dto.getIdTipologia(), dto.getIdSubtipologia()))
            throw new AttributeException("Ya existe un flujo con la misma combinación de Tipo de Demanda, Tipologia y Subtipologia.");

        return super.save(dto);
    }

    @Override
    protected WorkflowEntity nuevo(int id, WorkflowDto dto) {
        return new WorkflowEntity(id, dto.getNombre(), dto.getDescripcion(), dto.getIdTipoDemanda(),
                dto.getIdTipologia(), dto.getIdSubtipologia(), dto.getBpmn(), dto.getEstado());
    }

    @Override
    protected void actualizar(WorkflowEntity entity, WorkflowDto dto) {
        entity.setDescripcion(dto.getDescripcion());
        entity.setIdTipoDemanda(dto.getIdTipoDemanda());
        entity.setIdTipologia(dto.getIdTipologia());
        entity.setIdSubtipologia(dto.getIdSubtipologia());
        entity.setBpmn(dto.getBpmn());
    }

    private WorkflowListDto mapToResponseDto(WorkflowEntity workflow) {
        WorkflowListDto dto = new WorkflowListDto();
        dto.setId(workflow.getId());
        dto.setNombre(workflow.getNombre());
        dto.setDescripcion(workflow.getDescripcion());

        dto.setTipoDemanda(TipoDemanda.fromId(workflow.getIdTipoDemanda()).map(TipoDemanda::getNombre).orElse(null));

        dto.setTipologia(tipologiaRepository.findNombreById(workflow.getIdTipologia())
                .map(this::extractNombre)
                .orElse(null));
        dto.setSubtipologia(subtipologiaRepository.findNombreById(workflow.getIdSubtipologia())
                .map(this::extractNombre)
                .orElse(null));
        dto.setEstado(workflow.getEstado());
        return dto;
    }

    private String extractNombre(String jsonString) {
        try {
            JsonNode node = objectMapper.readTree(jsonString);
            return node.get("nombre").asText();
        } catch (Exception e) {
            return null;
        }
    }
}
