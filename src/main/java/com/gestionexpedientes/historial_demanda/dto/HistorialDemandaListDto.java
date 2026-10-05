package com.gestionexpedientes.historial_demanda.dto;

import java.util.Date;

public class HistorialDemandaListDto {
    private int id;
    private String Usuario;
    private String paso;
    private String idPaso;
    private int estado;
    private String observaciones;
    private Date fecha;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getUsuario() {
        return Usuario;
    }

    public void setUsuario(String usuario) {
        Usuario = usuario;
    }

    public String getPaso() {
        return paso;
    }

    public void setPaso(String paso) {
        this.paso = paso;
    }

    public String getIdPaso() {
        return idPaso;
    }

    public void setIdPaso(String idPaso) {
        this.idPaso = idPaso;
    }

    public int getEstado() {
        return estado;
    }

    public void setEstado(int estado) {
        this.estado = estado;
    }

    public String getObservaciones() {
        return observaciones;
    }

    public void setObservaciones(String observaciones) {
        this.observaciones = observaciones;
    }

    public Date getFecha() {
        return fecha;
    }

    public void setFecha(Date fecha) {
        this.fecha = fecha;
    }
}
