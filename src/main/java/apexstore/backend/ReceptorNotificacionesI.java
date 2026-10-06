package apexstore.backend;

import apexstore.NotificadorPagos;
import apexstore.RepositorioTransaccionesPrx;
import apexstore.ResultadoPago;
import com.zeroc.Ice.Current;

public final class ReceptorNotificacionesI implements NotificadorPagos {

    private final RepositorioTransaccionesPrx repositorio;

    public ReceptorNotificacionesI(RepositorioTransaccionesPrx repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    public void notificarResultado(ResultadoPago resultado, Current current) {
        boolean aplicado = repositorio.aplicarResultado(resultado);
        System.out.println("[BACKEND] Callback " + resultado.idTransaccion + " = " + resultado.estado
                + (aplicado ? " (orden actualizada)" : " (duplicado o tardio: ignorado)"));
    }
}