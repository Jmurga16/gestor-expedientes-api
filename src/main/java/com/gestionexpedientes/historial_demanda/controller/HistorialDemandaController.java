package com.gestionexpedientes.historial_demanda.controller;

import com.gestionexpedientes.demanda.service.DemandaService;
import com.gestionexpedientes.global.exceptions.ResourceNotFoundException;
import com.gestionexpedientes.historial_demanda.dto.HistorialDemandaListDto;
import com.gestionexpedientes.historial_demanda.service.HistorialDemandaService;
import com.gestionexpedientes.security.service.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/historial-demanda")
public class HistorialDemandaController {
    private final HistorialDemandaService historialDemandaService;
    private final DemandaService demandaService;

    public HistorialDemandaController(HistorialDemandaService historialDemandaService, DemandaService demandaService) {
        this.historialDemandaService = historialDemandaService;
        this.demandaService = demandaService;
    }

    @GetMapping("/{idDemanda}")
    public ResponseEntity<List<HistorialDemandaListDto>> getAll(@PathVariable("idDemanda") int idDemanda) throws ResourceNotFoundException {
        return ResponseEntity.ok(historialDemandaService.getDatatable(demandaService.getOne(idDemanda, CurrentUser.get())));
    }
}
