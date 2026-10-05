package com.gestionexpedientes.workflow.repository;

import com.gestionexpedientes.global.dto.BpmnDto;
import com.gestionexpedientes.global.repository.ICatalogRepository;
import com.gestionexpedientes.workflow.entity.WorkflowEntity;
import org.springframework.data.mongodb.repository.Query;

import java.util.Optional;

public interface IWorkflowRepository extends ICatalogRepository<WorkflowEntity> {

    boolean existsByIdTipoDemandaAndIdTipologiaAndIdSubtipologia(Integer idTipoDemanda, Integer idTipologia, Integer idSubtipologia);

    boolean existsByIdTipoDemandaAndIdTipologiaAndIdSubtipologiaAndEstado(Integer idTipoDemanda, Integer idTipologia,
                                                                      Integer idSubtipologia, int estado);

    @Query(value = "{ 'idTipoDemanda': ?0, 'idTipologia': ?1, 'idSubtipologia': ?2, 'estado': 1 }", fields = "{ 'bpmn': 1 }")
    Optional<BpmnDto> findBpmnByIdTipoDemandaAndIdTipologiaAndIdSubtipologia(Integer idTipoDemanda, Integer idTipologia, Integer idSubtipologia);
}
