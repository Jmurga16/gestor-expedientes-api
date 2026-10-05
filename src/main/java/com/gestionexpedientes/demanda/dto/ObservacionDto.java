package com.gestionexpedientes.demanda.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ObservacionDto(
        @NotBlank(message = "Escriba la observación.")
        @Size(max = 1000, message = "La observación admite hasta 1000 caracteres.") String observaciones) {
}
