package apexstore.backend;

import apexstore.*;
import com.zeroc.Ice.Current;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ServicioCheckoutI implements GestionCompras {

    private final RepositorioTransaccionesPrx repositorio;
    private final ProcesadorPagos procesadorPagos;

    public ServicioCheckoutI(RepositorioTransaccionesPrx repositorio, ProcesadorPagos procesadorPagos) {
        this.repositorio = repositorio;
        this.procesadorPagos = procesadorPagos;
    }

    @Override
    public String crearOrden(String idCliente, ItemOrden[] items, String moneda, Current current)
            throws OrdenInvalidaException {
        if (items == null || items.length == 0)
            throw new OrdenInvalidaException("La orden no tiene items");
        long total = 0;
        for (ItemOrden it : items) {
            if (it.cantidad <= 0 || it.precioUnitarioCentavos <= 0)
                throw new OrdenInvalidaException("Item invalido: " + it.sku);
            total = Math.addExact(total, Math.multiplyExact(it.precioUnitarioCentavos, (long) it.cantidad));
        }
        String idOrden = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        repositorio.guardarOrden(new Orden(idOrden, idCliente, items, total, moneda));
        System.out.println("[BACKEND] Orden " + idOrden + " creada por " + (total/100) + " " + moneda);
        return idOrden;
    }

    @Override
    public CompletionStage<RespuestaPago> pagarOrdenAsync(String idOrden, String metodo,
                                                          String tokenMedioPago, Current current) {
        Orden orden;
        try {
            orden = repositorio.obtenerOrden(idOrden);
        } catch (NoEncontradoException e) {
            return CompletableFuture.failedFuture(new PagoException("Orden inexistente: " + idOrden));
        }
        return procesadorPagos.iniciarPagoOrden(orden, metodo, tokenMedioPago);
    }

    @Override
    public EstadoOrden consultarEstadoOrden(String idOrden, Current current) throws NoEncontradoException {
        return repositorio.consultarEstadoOrden(idOrden);
    }
}