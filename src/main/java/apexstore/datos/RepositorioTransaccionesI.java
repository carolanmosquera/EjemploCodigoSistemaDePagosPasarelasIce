package apexstore.datos;

import apexstore.EstadoOrden;
import apexstore.EstadoPago;
import apexstore.NoEncontradoException;
import apexstore.Orden;
import apexstore.RepositorioTransacciones;
import apexstore.ResultadoPago;
import com.zeroc.Ice.Current;

import java.time.Instant;
import java.util.*;

public class RepositorioTransaccionesI implements RepositorioTransacciones {

    private static final class Transaccion {
        final String idTransaccion, idOrden, metodo, moneda;
        final long montoCentavos;
        EstadoPago estado = EstadoPago.PENDIENTE;
        String referenciaExterna = "";
        String detalle = "Pago registrado, esperando confirmacion";

        Transaccion(String id, String idOrden, String metodo, long monto, String moneda) {
            this.idTransaccion = id; this.idOrden = idOrden; this.metodo = metodo;
            this.montoCentavos = monto; this.moneda = moneda;
        }
    }

    private final Map<String, Orden> ordenes = new HashMap<>();
    private final Map<String, Transaccion> transacciones = new HashMap<>();
    private final Map<String, String> ultimaTxPorOrden = new HashMap<>();
    private final Map<String, String> pagoAprobadoPorOrden = new HashMap<>();

    @Override
    public synchronized void guardarOrden(Orden orden, Current current) {
        ordenes.put(orden.idOrden, orden);
        auditar("ORDEN_CREADA", orden.idOrden, "total=" + orden.totalCentavos + " " + orden.moneda);
    }

    @Override
    public synchronized Orden obtenerOrden(String idOrden, Current current) throws NoEncontradoException {
        Orden o = ordenes.get(idOrden);
        if (o == null) throw new NoEncontradoException(idOrden);
        return o;
    }

    @Override
    public synchronized boolean registrarTransaccion(String idTransaccion, String idOrden, String metodo,
                                                     long montoCentavos, String moneda, Current current)
            throws NoEncontradoException {
        if (!ordenes.containsKey(idOrden)) throw new NoEncontradoException(idOrden);
        if (transacciones.containsKey(idTransaccion)) return false;
        if (pagoAprobadoPorOrden.containsKey(idOrden)) {
            auditar("PAGO_RECHAZADO_ORDEN_PAGADA", idOrden, "intento=" + idTransaccion);
            return false;
        }
        String ultima = ultimaTxPorOrden.get(idOrden);
        if (ultima != null && transacciones.get(ultima).estado == EstadoPago.PENDIENTE) {
            auditar("PAGO_RECHAZADO_EN_CURSO", idOrden, "intento=" + idTransaccion + " en_curso=" + ultima);
            return false;
        }
        transacciones.put(idTransaccion, new Transaccion(idTransaccion, idOrden, metodo, montoCentavos, moneda));
        ultimaTxPorOrden.put(idOrden, idTransaccion);
        auditar("PAGO_REGISTRADO", idTransaccion, "orden=" + idOrden + " metodo=" + metodo);
        return true;
    }

    @Override
    public synchronized boolean aplicarResultado(ResultadoPago r, Current current) {
        Transaccion tx = transacciones.get(r.idTransaccion);
        if (tx == null || r.estado == EstadoPago.PENDIENTE) {
            auditar("RESULTADO_IGNORADO", r.idTransaccion, "tx desconocida o no final");
            return false;
        }
        boolean pendiente = tx.estado == EstadoPago.PENDIENTE;
        boolean tardia = tx.estado == EstadoPago.FALLIDO && r.estado == EstadoPago.APROBADO;
        if (!pendiente && !tardia) {
            auditar("RESULTADO_DUPLICADO", r.idTransaccion, "estado_actual=" + tx.estado);
            return false;
        }
        EstadoPago nuevo = r.estado;
        String detalle = r.detalle;
        if (r.estado == EstadoPago.APROBADO) {
            String ya = pagoAprobadoPorOrden.get(tx.idOrden);
            if (ya != null && !ya.equals(tx.idTransaccion)) {
                nuevo = EstadoPago.PORREVERSAR;
                detalle = "Orden ya pagada por " + ya + "; este cobro debe reversarse";
            } else {
                pagoAprobadoPorOrden.put(tx.idOrden, tx.idTransaccion);
            }
        }
        tx.estado = nuevo;
        tx.referenciaExterna = r.referenciaExterna;
        tx.detalle = detalle;
        auditar("RESULTADO_APLICADO", tx.idTransaccion, "orden=" + tx.idOrden + " estado=" + nuevo);
        return true;
    }

    @Override
    public synchronized EstadoOrden consultarEstadoOrden(String idOrden, Current current)
            throws NoEncontradoException {
        if (!ordenes.containsKey(idOrden)) throw new NoEncontradoException(idOrden);
        String idTx = pagoAprobadoPorOrden.getOrDefault(idOrden, ultimaTxPorOrden.get(idOrden));
        if (idTx == null)
            return new EstadoOrden(idOrden, "", EstadoPago.PENDIENTE, "Orden sin pagos registrados");
        Transaccion tx = transacciones.get(idTx);
        return new EstadoOrden(idOrden, idTx, tx.estado, tx.detalle);
    }

    private void auditar(String evento, String id, String datos) {
        System.out.println("[AUDITORIA] " + Instant.now() + " | " + evento + " | " + id + " | " + datos);
    }
}