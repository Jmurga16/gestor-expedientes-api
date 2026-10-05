package com.gestionexpedientes.demanda.service;

import com.gestionexpedientes.global.exceptions.AttributeException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final Set<String> COMPUERTAS_PARALELAS = Set.of("parallelGateway", "inclusiveGateway", "complexGateway");
    private static final Set<String> COMPUERTAS_DE_DECISION = Set.of("exclusiveGateway", "eventBasedGateway");

    private BpmnSteps() {
    }

    public record Pasos(Map<String, String> nombrePorId, Map<String, Integer> areaPorId, Integer areaInicial) {
        public Set<String> tareas() {
            return new LinkedHashSet<>(nombrePorId.values());
        }

        public List<String> idsDe(String nombre) {
            List<String> ids = new ArrayList<>();
            nombrePorId.forEach((id, actual) -> {
                if (actual.equals(nombre))
                    ids.add(id);
            });
            return ids;
        }

        public Integer areaDeTarea(String id) {
            return areaPorId.get(id);
        }

        public Integer areaDe(String paso) {
            if (PASO_INICIAL.equals(paso))
                return areaInicial;
            List<String> ids = idsDe(paso);
            return ids.size() == 1 ? areaPorId.get(ids.get(0)) : null;
        }
    }

    static Set<String> parse(String xml) throws AttributeException {
        return leer(xml).tareas();
    }

    public static Pasos leer(String xml) throws AttributeException {
        List<Element> elementos = elementos(xml);

        Map<String, String> nombrePorId = new LinkedHashMap<>();
        Map<String, Integer> areaNodo = new HashMap<>();
        Set<String> inicios = new HashSet<>();
        Map<String, Set<String>> salidas = new HashMap<>();
        for (Element node : elementos) {
            String tipo = node.getLocalName();
            if (TASKS.contains(tipo) && !node.getAttribute("name").isBlank())
                nombrePorId.put(node.getAttribute("id").isBlank() ? "#" + nombrePorId.size() : node.getAttribute("id"),
                        node.getAttribute("name"));
            else if ("startEvent".equals(tipo))
                inicios.add(node.getAttribute("id"));
            else if ("sequenceFlow".equals(tipo))
                salidas.computeIfAbsent(node.getAttribute("sourceRef"), k -> new HashSet<>()).add(node.getAttribute("targetRef"));
            else if ("lane".equals(tipo))
                registrarCarril(node, areaNodo);
        }

        Map<String, Integer> areaPorId = new HashMap<>();
        nombrePorId.keySet().forEach(id -> {
            if (areaNodo.containsKey(id))
                areaPorId.put(id, areaNodo.get(id));
        });

        Set<Integer> areasIniciales = new HashSet<>();
        inicios.forEach(inicio -> salidas.getOrDefault(inicio, Set.of()).stream()
                .filter(nombrePorId::containsKey)
                .forEach(destino -> areasIniciales.add(areaNodo.get(destino))));
        Integer areaInicial = areasIniciales.size() == 1 ? areasIniciales.iterator().next() : null;

        return new Pasos(nombrePorId, areaPorId, areaInicial);
    }

    public static void validarSecuencial(String xml) throws AttributeException {
        Map<String, Element> nodos = new HashMap<>();
        Map<String, Integer> cantidadSalidas = new HashMap<>();
        for (Element node : elementos(xml)) {
            String tipo = node.getLocalName();
            if (COMPUERTAS_PARALELAS.contains(tipo))
                throw new AttributeException("El circuito debe ser secuencial: quite la compuerta "
                        + describir(node) + " y use compuertas exclusivas para las decisiones.");
            if ("sequenceFlow".equals(tipo))
                cantidadSalidas.merge(node.getAttribute("sourceRef"), 1, Integer::sum);
            else if (!node.getAttribute("id").isBlank())
                nodos.put(node.getAttribute("id"), node);
        }

        for (Map.Entry<String, Integer> salida : cantidadSalidas.entrySet()) {
            Element origen = nodos.get(salida.getKey());
            if (salida.getValue() > 1 && origen != null && !COMPUERTAS_DE_DECISION.contains(origen.getLocalName()))
                throw new AttributeException("El circuito debe ser secuencial: " + describir(origen)
                        + " tiene varias salidas. Use una compuerta exclusiva para elegir un solo camino.");
        }
    }

    private static String describir(Element node) {
        String nombre = node.getAttribute("name");
        return "«" + (nombre.isBlank() ? node.getAttribute("id") : nombre) + "»";
    }

    private static List<Element> elementos(String xml) throws AttributeException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            NodeList nodes = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)))
                    .getElementsByTagNameNS(BPMN_NS, "*");
            List<Element> elementos = new ArrayList<>(nodes.getLength());
            for (int i = 0; i < nodes.getLength(); i++)
                elementos.add((Element) nodes.item(i));
            return elementos;
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
