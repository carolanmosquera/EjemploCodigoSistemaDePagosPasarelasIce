package apexstore.backend;

import apexstore.Orden;
import apexstore.RespuestaPago;
import java.util.concurrent.CompletableFuture;

/** Interfaz local (no Slice) — inversión de dependencias dentro del Nodo 2. */
public interface ProcesadorPagos {
    CompletableFuture<RespuestaPago> iniciarPagoOrden(Orden orden, String metodo, String tokenMedioPago);
}