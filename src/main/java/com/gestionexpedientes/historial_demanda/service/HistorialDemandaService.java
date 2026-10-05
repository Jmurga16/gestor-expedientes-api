package com.gestionexpedientes.historial_demanda.service;

import com.gestionexpedientes.demanda.entity.DemandaEntity;
import com.gestionexpedientes.historial_demanda.dto.HistorialDemandaListDto;
import com.gestionexpedientes.historial_demanda.entity.RegistroHistorial;
import com.gestionexpedientes.historial_demanda.repository.IHistorialDemandaRepository;
import com.gestionexpedientes.user.repository.IUserRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class HistorialDemandaService {
    private final IHistorialDemandaRepository historialDemandaRepository;
    private final IUserRepository userRepository;

    public HistorialDemandaService(IHistorialDemandaRepository historialDemandaRepository, IUserRepository userRepository) {
        this.historialDemandaRepository = historialDemandaRepository;
        this.userRepository = userRepository;
    }

    public List<HistorialDemandaListDto> getDatatable(DemandaEntity demanda) {
        List<RegistroHistorial> registros = new ArrayList<>();
        historialDemandaRepository.findByIdDemanda(demanda.getId()).forEach(item -> registros.add(new RegistroHistorial(
                item.getIdUsuario(), item.getPaso(), null, item.getEstado(), item.getObservaciones(), item.getFecha())));
        if (demanda.getHistorial() != null)
            registros.addAll(demanda.getHistorial());
        registros.sort(Comparator.comparing(RegistroHistorial::fecha, Comparator.nullsFirst(Comparator.naturalOrder())));

        List<Integer> idsUsuario = registros.stream().map(RegistroHistorial::idUsuario).distinct().collect(Collectors.toList());
        Map<Integer, String> nombres = new HashMap<>();
        userRepository.findAllById(idsUsuario).forEach(user -> nombres.put(user.getId(), user.getName() + " " + user.getLastname()));

        List<HistorialDemandaListDto> lista = new ArrayList<>();
        for (RegistroHistorial registro : registros)
            lista.add(mapToListDto(lista.size() + 1, registro, nombres.get(registro.idUsuario())));
        return lista;
    }

    private HistorialDemandaListDto mapToListDto(int id, RegistroHistorial registro, String usuario) {
        HistorialDemandaListDto dto = new HistorialDemandaListDto();
        dto.setId(id);
        dto.setPaso(registro.paso());
        dto.setIdPaso(registro.idPaso());
        dto.setEstado(registro.estado());
        dto.setObservaciones(registro.observaciones());
        dto.setFecha(registro.fecha());
        dto.setUsuario(usuario);
        return dto;
    }
}
