package apexstore.backend;

import apexstore.*;
import com.zeroc.Ice.LocalException;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.*;

public final class ProcesadorPagosContexto implements ProcesadorPagos {

    private final RegistroEstrategias registro;
    private final RepositorioTransaccionesPrx repositorio;
    private final NotificadorPagosPrx notificador;
    private final int timeoutAcuseMs;
    private final Executor ejecutorPosproceso;

    public ProcesadorPagosContexto(RegistroEstrategias registro,
                                   RepositorioTransaccionesPrx repositorio,
                                   NotificadorPagosPrx notificador,
                                   int timeoutAcuseMs,
                                   Executor ejecutorPosproceso) {
        this.registro = registro;
        this.repositorio = repositorio;
        this.notificador = notificador;
        this.timeoutAcuseMs = timeoutAcuseMs;
        this.ejecutorPosproceso = ejecutorPosproceso;
    }

    @Override
    public CompletableFuture<RespuestaPago> iniciarPagoOrden(Orden orden, String metodo, String token) {
        String clave = metodo == null ? "" : metodo.trim().toUpperCase(Locale.ROOT);

        EstrategiaPagoPrx estrategia = registro.obtener(clave);
        if (estrategia == null)
            return CompletableFuture.failedFuture(new MetodoNoSoportadoException(
                    "Medio no soportado: " + metodo + ". Disponibles: " + registro.metodosSoportados()));

        CircuitBreaker breaker = registro.breaker(clave);
        if (!breaker.permiteLlamada())
            return CompletableFuture.failedFuture(new PasarelaNoDisponibleException(
                    clave + " no disponible, intente con otro medio"));

        String idTx = "TX-" + UUID.randomUUID();
        try {
            boolean reg = repositorio.registrarTransaccion(idTx, orden.idOrden, clave,
                    orden.totalCentavos, orden.moneda);
            if (!reg)
                return CompletableFuture.failedFuture(new PagoException(
                        "La orden " + orden.idOrden + " ya tiene un pago aprobado o en curso"));
        } catch (NoEncontradoException e) {
            return CompletableFuture.failedFuture(new PagoException("Orden inexistente: " + e.id));
        } catch (LocalException e) {
            return CompletableFuture.failedFuture(new PasarelaNoDisponibleException(
                    "Repositorio no disponible; no se inicia ningun cobro"));
        }

        SolicitudPago sol = new SolicitudPago(idTx, orden.idOrden, orden.totalCentavos,
                orden.moneda, clave, token);

        EstrategiaPagoPrx conTimeout =
                EstrategiaPagoPrx.uncheckedCast(estrategia.ice_invocationTimeout(timeoutAcuseMs));
        long inicio = System.nanoTime();

        return conTimeout.procesarPagoAsync(sol, notificador)
                .handleAsync((resp, err) -> {
                    long ms = (System.nanoTime() - inicio) / 1_000_000;
                    if (err == null) {
                        breaker.registrarExito();
                        System.out.println("[BACKEND] Acuse " + clave + " en " + ms + " ms: " + resp.mensaje);
                        return resp;
                    }
                    Throwable causa = causaRaiz(err);
                    if (causa instanceof PagoException) {
                        breaker.registrarExito();
                        cerrarTx(idTx, EstadoPago.RECHAZADO, ((PagoException) causa).razon);
                        throw new CompletionException(causa);
                    }
                    breaker.registrarFallo();
                    cerrarTx(idTx, EstadoPago.FALLIDO,
                            clave + " sin respuesta tras " + ms + " ms (" + causa.getClass().getSimpleName() + ")");
                    throw new CompletionException(new PasarelaNoDisponibleException(
                            clave + " no respondio a tiempo; intente de nuevo o use otro medio"));
                }, ejecutorPosproceso);
    }

    private void cerrarTx(String idTx, EstadoPago estado, String detalle) {
        try {
            repositorio.aplicarResultado(new ResultadoPago(idTx, "", estado, detalle));
        } catch (LocalException e) {
            System.err.println("[BACKEND] No se pudo cerrar " + idTx + ": " + e);
        }
    }

    private static Throwable causaRaiz(Throwable t) {
        Throwable a = t;
        while ((a instanceof CompletionException || a instanceof ExecutionException) && a.getCause() != null)
            a = a.getCause();
        return a;
    }
}