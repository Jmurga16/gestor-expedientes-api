package com.gestionexpedientes.demanda.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MovimientoDto(
        @NotBlank(message = "El paso es obligatorio.") String paso,
        @NotNull(message = "El estado es obligatorio.") Integer estado,
        @Size(max = 1000, message = "El motivo admite hasta 1000 caracteres.") String observaciones,
        @NotNull(message = "Falta la versión del expediente; vuelva a cargarlo.") Long version) {
}
