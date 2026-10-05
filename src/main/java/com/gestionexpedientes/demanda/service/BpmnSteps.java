package com.gestionexpedientes.demanda.service;

import com.gestionexpedientes.global.exceptions.AttributeException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BpmnSteps {
    public static final String PASO_INICIAL = "Inicio";

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final Pattern AREA_LANE = Pattern.compile("Lane_(\\d+)");
    private static final Set<String> TASKS = Set.of("task", "userTask", "serviceTask", "manualTask",
            "scriptTask", "businessRuleTask", "sendTask", "receiveTask");

    private BpmnSteps() {
    }

    public record Pasos(Set<String> tareas, Map<String, Integer> areas) {
        public Integer areaDe(String paso) {
            return areas.get(paso);
        }
    }

    static Set<String> parse(String xml) throws AttributeException {
        return leer(xml).tareas();
    }

    public static Pasos leer(String xml) throws AttributeException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            NodeList nodes = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)))
                    .getElementsByTagNameNS(BPMN_NS, "*");

            Set<String> tareas = new LinkedHashSet<>();
            Map<String, String> nombreTarea = new HashMap<>();
            Map<String, Integer> areaNodo = new HashMap<>();
            Set<String> inicios = new HashSet<>();
            Map<String, Set<String>> salidas = new HashMap<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Element node = (Element) nodes.item(i);
                String tipo = node.getLocalName();
                if (TASKS.contains(tipo) && !node.getAttribute("name").isBlank()) {
                    tareas.add(node.getAttribute("name"));
                    nombreTarea.put(node.getAttribute("id"), node.getAttribute("name"));
                }
                else if ("startEvent".equals(tipo))
                    inicios.add(node.getAttribute("id"));
                else if ("sequenceFlow".equals(tipo))
                    salidas.computeIfAbsent(node.getAttribute("sourceRef"), k -> new HashSet<>()).add(node.getAttribute("targetRef"));
                else if ("lane".equals(tipo))
                    registrarCarril(node, areaNodo);
            }

            Map<String, Set<Integer>> candidatas = new HashMap<>();
            nombreTarea.forEach((id, nombre) -> candidatas.computeIfAbsent(nombre, k -> new HashSet<>()).add(areaNodo.get(id)));
            inicios.forEach(inicio -> salidas.getOrDefault(inicio, Set.of()).stream()
                    .filter(nombreTarea::containsKey)
                    .forEach(destino -> candidatas.computeIfAbsent(PASO_INICIAL, k -> new HashSet<>()).add(areaNodo.get(destino))));

            Map<String, Integer> areas = new HashMap<>();
            candidatas.forEach((paso, ids) -> {
                if (ids.size() == 1 && ids.iterator().next() != null)
                    areas.put(paso, ids.iterator().next());
            });
            return new Pasos(tareas, areas);
        } catch (Exception e) {
            throw new AttributeException("No se pudieron validar los pasos del diagrama BPMN.");
        }
    }

    private static void registrarCarril(Element lane, Map<String, Integer> areaNodo) {
        Matcher matcher = AREA_LANE.matcher(lane.getAttribute("id"));
        if (!matcher.matches())
            return;
        int idArea = Integer.parseInt(matcher.group(1));
        for (Node hijo = lane.getFirstChild(); hijo != null; hijo = hijo.getNextSibling())
            if ("flowNodeRef".equals(hijo.getLocalName()))
                areaNodo.put(hijo.getTextContent().trim(), idArea);
    }
}
