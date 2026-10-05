package com.gestionexpedientes.demanda.service;

import com.gestionexpedientes.demanda.dto.PermisosDemandaDto;
import com.gestionexpedientes.demanda.entity.DemandaEntity;
import com.gestionexpedientes.security.service.UserPrincipal;
import com.gestionexpedientes.security.enums.RoleEnum;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class DemandaAccessService {

    public static final int ESTADO_RECEPTADA = 1;
    public static final int ESTADO_FINALIZADO = 7;

    public static boolean esTerminal(int estado) {
        return estado == 4 || estado == 5 || estado == ESTADO_FINALIZADO;
    }

    public boolean canAccess(DemandaEntity demanda, UserPrincipal user) {
        if (user.isAdmin() || esSolicitante(demanda, user))
            return true;
        return user.isAreaStaff() && participaSuArea(demanda, user);
    }

    public void checkAccess(DemandaEntity demanda, UserPrincipal user) {
        if (!canAccess(demanda, user))
            throw new AccessDeniedException("Sin acceso al expediente " + demanda.getId());
    }

    public boolean canAdvance(DemandaEntity demanda, UserPrincipal user) {
        return !esTerminal(demanda.getEstado()) && (user.isAdmin() || esReferenteResponsable(demanda, user));
    }

    public boolean canReopen(DemandaEntity demanda, UserPrincipal user) {
        return esTerminal(demanda.getEstado()) && user.isAdmin();
    }

    public boolean canEdit(DemandaEntity demanda, UserPrincipal user) {
        return user.isAdmin() || esReferenteResponsable(demanda, user)
                || (esSolicitante(demanda, user) && sinTomar(demanda));
    }

    public boolean canDelete(DemandaEntity demanda, UserPrincipal user) {
        return !esTerminal(demanda.getEstado())
                && (user.isAdmin() || (esSolicitante(demanda, user) && sinTomar(demanda)));
    }

    public boolean canObserve(DemandaEntity demanda, UserPrincipal user) {
        return user.isAdmin() || (user.isAreaStaff() && participaSuArea(demanda, user));
    }

    public PermisosDemandaDto permisos(DemandaEntity demanda, UserPrincipal user) {
        return new PermisosDemandaDto(canAdvance(demanda, user), canEdit(demanda, user), canDelete(demanda, user),
                canObserve(demanda, user), canReopen(demanda, user));
    }

    private static boolean esReferenteResponsable(DemandaEntity demanda, UserPrincipal user) {
        if (!user.hasRole(RoleEnum.ROLE_AREA) || user.getIdArea() == null)
            return false;
        if (demanda.getIdAreaPaso() != null)
            return demanda.getIdAreaPaso().equals(user.getIdArea());
        return participaSuArea(demanda, user);
    }

    private static boolean participaSuArea(DemandaEntity demanda, UserPrincipal user) {
        return user.getIdArea() != null && demanda.getIdsArea() != null && demanda.getIdsArea().contains(user.getIdArea());
    }

    private static boolean esSolicitante(DemandaEntity demanda, UserPrincipal user) {
        return demanda.getIdUsuario() == user.getId();
    }

    private static boolean sinTomar(DemandaEntity demanda) {
        return demanda.getEstado() == ESTADO_RECEPTADA && Objects.equals(demanda.getPaso(), BpmnSteps.PASO_INICIAL);
    }
}
