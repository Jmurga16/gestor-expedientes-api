package com.gestionexpedientes.demanda.dto;

import jakarta.validation.constraints.NotNull;

public class DemandaRequestDto {

    private Integer idUsuario;
    private String caratula;
    @NotNull(message = "Tipo de demanda es obligatorio")
    private Integer idTipoDemanda;
    @NotNull(message = "Tipologia es obligatorio")
    private Integer idTipologia;
    @NotNull(message = "Subtipologia es obligatorio")
    private Integer idSubtipologia;
    private String domicilio;
    private String rutaImagen;
    private String informacionAdicional;
    private String urlBpmn;
    private Long version;

    public DemandaRequestDto() {
    }

    public Integer getIdUsuario() {
        return idUsuario;
    }

    public void setIdUsuario(Integer idUsuario) {
        this.idUsuario = idUsuario;
    }

    public String getCaratula() {
        return caratula;
    }

    public void setCaratula(String caratula) {
        this.caratula = caratula;
    }

    public Integer getIdTipoDemanda() {
        return idTipoDemanda;
    }

    public void setIdTipoDemanda(Integer idTipoDemanda) {
        this.idTipoDemanda = idTipoDemanda;
    }

    public Integer getIdTipologia() {
        return idTipologia;
    }

    public void setIdTipologia(Integer idTipologia) {
        this.idTipologia = idTipologia;
    }

    public Integer getIdSubtipologia() {
        return idSubtipologia;
    }

    public void setIdSubtipologia(Integer idSubtipologia) {
        this.idSubtipologia = idSubtipologia;
    }

    public String getDomicilio() {
        return domicilio;
    }

    public void setDomicilio(String domicilio) {
        this.domicilio = domicilio;
    }

    public String getRutaImagen() {
        return rutaImagen;
    }

    public void setRutaImagen(String rutaImagen) {
        this.rutaImagen = rutaImagen;
    }

    public String getInformacionAdicional() {
        return informacionAdicional;
    }

    public void setInformacionAdicional(String informacionAdicional) {
        this.informacionAdicional = informacionAdicional;
    }

    public String getUrlBpmn() {
        return urlBpmn;
    }

    public void setUrlBpmn(String urlBpmn) {
        this.urlBpmn = urlBpmn;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
