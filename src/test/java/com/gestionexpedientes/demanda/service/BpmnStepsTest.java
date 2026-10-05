package com.gestionexpedientes.demanda.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class BpmnStepsTest {
    @Test
    void reconoceSubtiposYSubprocesosSinConfundirOtrosNamespaces() throws Exception {
        assertThat(BpmnSteps.parse("""
                <b:definitions xmlns:b="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:x="urn:otro">
                  <b:process><b:task name="Recepción"/><b:subProcess><b:userTask name="Revisión"/>
                  <b:serviceTask name="Consulta"/><b:task/></b:subProcess><x:task name="Falso"/></b:process>
                </b:definitions>
                """)).containsExactlyInAnyOrder("Recepción", "Revisión", "Consulta");
    }

    @Test
    void rechazaDoctypeYXmlInvalido() {
        assertThatThrownBy(() -> BpmnSteps.parse("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///no-leer'>]><x/>"))
                .hasMessageContaining("No se pudieron validar");
        assertThatThrownBy(() -> BpmnSteps.parse("no es XML")).hasMessageContaining("No se pudieron validar");
    }

    @Test
    void asignaCadaPasoAlAreaDeSuCarrilEInicioALaPrimeraTarea() throws Exception {
        BpmnSteps.Pasos pasos = BpmnSteps.leer(new String(
                getClass().getResourceAsStream("/seed/bpmn/workflow-06.bpmn").readAllBytes(), StandardCharsets.UTF_8));

        assertThat(pasos.areaDe("Inicio")).isEqualTo(5);
        assertThat(pasos.areaDe("Inspección del bache")).isEqualTo(5);
        assertThat(pasos.areaDe("Limpieza del sector")).isEqualTo(4);
        assertThat(pasos.areaDe("Finalizado")).isNull();
    }

    @Test
    void sinCarrilDeAreaONombreAmbiguoNoTieneResponsable() throws Exception {
        BpmnSteps.Pasos pasos = BpmnSteps.leer("""
                <b:definitions xmlns:b="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <b:process><b:laneSet>
                    <b:lane id="Lane_4"><b:flowNodeRef>T1</b:flowNodeRef></b:lane>
                    <b:lane id="Lane_5"><b:flowNodeRef>T2</b:flowNodeRef></b:lane>
                    <b:lane id="Lane_Vecino"><b:flowNodeRef>T3</b:flowNodeRef></b:lane>
                  </b:laneSet>
                  <b:task id="T1" name="Revisión"/><b:task id="T2" name="Revisión"/><b:task id="T3" name="Firma"/>
                  </b:process>
                </b:definitions>
                """);

        assertThat(pasos.tareas()).containsExactlyInAnyOrder("Revisión", "Firma");
        assertThat(pasos.areaDe("Revisión")).isNull();
        assertThat(pasos.areaDe("Firma")).isNull();
        assertThat(pasos.areaDe("Inicio")).isNull();
    }

    @Test
    void losCircuitosDeLaSemillaSonSecuenciales() throws Exception {
        for (int i = 1; i <= 14; i++) {
            String recurso = String.format("/seed/bpmn/workflow-%02d.bpmn", i);
            BpmnSteps.validarSecuencial(new String(getClass().getResourceAsStream(recurso).readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void rechazaRamasEnParalelo() {
        assertThatThrownBy(() -> BpmnSteps.validarSecuencial("""
                <b:definitions xmlns:b="http://www.omg.org/spec/BPMN/20100524/MODEL"><b:process>
                  <b:parallelGateway id="G1" name="Dividir"/>
                </b:process></b:definitions>
                """)).hasMessageContaining("secuencial").hasMessageContaining("Dividir");

        assertThatThrownBy(() -> BpmnSteps.validarSecuencial("""
                <b:definitions xmlns:b="http://www.omg.org/spec/BPMN/20100524/MODEL"><b:process>
                  <b:task id="T1" name="Revisión"/><b:task id="T2"/><b:task id="T3"/>
                  <b:sequenceFlow id="F1" sourceRef="T1" targetRef="T2"/>
                  <b:sequenceFlow id="F2" sourceRef="T1" targetRef="T3"/>
                </b:process></b:definitions>
                """)).hasMessageContaining("varias salidas").hasMessageContaining("Revisión");
    }

    @Test
    void admiteDecisionesConCompuertaExclusiva() throws Exception {
        BpmnSteps.validarSecuencial("""
                <b:definitions xmlns:b="http://www.omg.org/spec/BPMN/20100524/MODEL"><b:process>
                  <b:task id="T1"/><b:exclusiveGateway id="G1"/><b:task id="T2"/><b:task id="T3"/>
                  <b:sequenceFlow id="F1" sourceRef="T1" targetRef="G1"/>
                  <b:sequenceFlow id="F2" sourceRef="G1" targetRef="T2"/>
                  <b:sequenceFlow id="F3" sourceRef="G1" targetRef="T3"/>
                </b:process></b:definitions>
                """);
    }
}
