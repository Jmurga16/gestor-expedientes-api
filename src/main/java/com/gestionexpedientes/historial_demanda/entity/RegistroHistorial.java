package com.gestionexpedientes.historial_demanda.entity;

import java.util.Date;

public record RegistroHistorial(int idUsuario, String paso, String idPaso, int estado, String observaciones, Date fecha) {
}
