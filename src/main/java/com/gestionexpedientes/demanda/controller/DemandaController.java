package com.gestionexpedientes.demanda.controller;

import com.gestionexpedientes.demanda.dto.DemandaRequestDto;
import com.gestionexpedientes.demanda.dto.DemandaListDto;
import com.gestionexpedientes.demanda.dto.MovimientoDto;
import com.gestionexpedientes.demanda.dto.ObservacionDto;
import com.gestionexpedientes.demanda.dto.PermisosDemandaDto;
import com.gestionexpedientes.demanda.entity.DemandaEntity;
import com.gestionexpedientes.demanda.service.DemandaExcelService;
import com.gestionexpedientes.demanda.service.DemandaService;
import com.gestionexpedientes.global.dto.MessageDto;
import com.gestionexpedientes.global.dto.PageDto;
import com.gestionexpedientes.global.exceptions.AttributeException;
import com.gestionexpedientes.global.exceptions.ResourceNotFoundException;
import com.gestionexpedientes.security.service.CurrentUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/demanda")
public class DemandaController {
    private static final String EXCEL_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final DemandaService demandaService;
    private final DemandaExcelService demandaExcelService;

    public DemandaController(DemandaService demandaService, DemandaExcelService demandaExcelService) {
        this.demandaService = demandaService;
        this.demandaExcelService = demandaExcelService;
    }

    @GetMapping
    public ResponseEntity<PageDto<DemandaListDto>> getAll(@RequestParam(required = false) String search,
                                                          @RequestParam(defaultValue = "1") int pageIndex,
                                                          @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.ok(demandaService.getDatatable(search, pageIndex, pageSize, CurrentUser.get()));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String search) throws IOException {
        byte[] contenido = demandaExcelService.generar(demandaService.getExport(search, CurrentUser.get()));

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(EXCEL_MEDIA_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"expedientes.xlsx\"")
                .body(contenido);
    }

    @GetMapping("/resumen")
    public ResponseEntity<Map<Integer, Long>> getResumen() {
        return ResponseEntity.ok(demandaService.getResumen(CurrentUser.get()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/activos")
    public ResponseEntity<List<DemandaEntity>> getActives() {
        return ResponseEntity.ok(demandaService.getActives());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DemandaEntity> getOne(@PathVariable("id") int id) throws ResourceNotFoundException {
        return ResponseEntity.ok(demandaService.getOne(id, CurrentUser.get()));
    }

    @GetMapping("/{id}/permisos")
    public ResponseEntity<PermisosDemandaDto> getPermisos(@PathVariable("id") int id) throws ResourceNotFoundException {
        return ResponseEntity.ok(demandaService.getPermisos(id, CurrentUser.get()));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> save(@Valid @RequestBody DemandaRequestDto dto) throws Exception {
        DemandaEntity demanda = demandaService.save(dto, CurrentUser.get());
        String message = demanda.getCaratula() + " ha sido guardado";

        Map<String, Object> response = new HashMap<>();
        response.put("status", HttpStatus.OK);
        response.put("message", message);
        response.put("id", demanda.getId());

        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<MessageDto> update(@PathVariable("id") int id, @Valid @RequestBody DemandaRequestDto dto) throws ResourceNotFoundException, AttributeException {
        DemandaEntity demanda = demandaService.update(id, dto, CurrentUser.get());
        String message = demanda.getCaratula() + " ha sido actualizado";
        return ResponseEntity.ok(new MessageDto(HttpStatus.OK, message));
    }

    @PostMapping("/{id}/movimiento")
    public ResponseEntity<MessageDto> mover(@PathVariable("id") int id, @Valid @RequestBody MovimientoDto dto) throws ResourceNotFoundException, AttributeException {
        DemandaEntity demanda = demandaService.mover(id, dto, CurrentUser.get());
        return ResponseEntity.ok(new MessageDto(HttpStatus.OK, demanda.getCaratula() + " ha sido actualizado"));
    }

    @PostMapping("/{id}/observacion")
    public ResponseEntity<MessageDto> observar(@PathVariable("id") int id, @Valid @RequestBody ObservacionDto dto) throws ResourceNotFoundException {
        demandaService.observar(id, dto, CurrentUser.get());
        return ResponseEntity.ok(new MessageDto(HttpStatus.OK, "Observación registrada en el historial"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<MessageDto> delete(@PathVariable("id") int id) throws ResourceNotFoundException, AttributeException {
        DemandaEntity demanda = demandaService.delete(id, CurrentUser.get());
        String message = demanda.getCaratula() + " ha sido eliminado";
        return ResponseEntity.ok(new MessageDto(HttpStatus.OK, message));
    }
}
