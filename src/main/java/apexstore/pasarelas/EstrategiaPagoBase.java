package apexstore.pasarelas;

import apexstore.*;
import com.zeroc.Ice.Current;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class EstrategiaPagoBase implements EstrategiaPago {

    private static final int MAX_INTENTOS_CALLBACK = 3;
    private static final ScheduledExecutorService PLANIFICADOR =
        Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "pasarelas-planificador");
            t.setDaemon(true);
            return t;
        });

    private final Map<String, EstadoPago> estados = new ConcurrentHashMap<>();

    protected abstract String metodo();
    protected abstract void validar(SolicitudPago s) throws PagoException;
    protected abstract String traducirAFormatoProveedor(SolicitudPago s);
    protected abstract long retardoConfirmacionMs();

    @Override
    public RespuestaPago procesarPago(SolicitudPago s, NotificadorPagosPrx callback, Current current)
            throws PagoException {
        if (s.tokenMedioPago == null || s.tokenMedioPago.isBlank())
            throw new PagoException("Token de medio de pago vacio");
        if (s.montoCentavos <= 0)
            throw new PagoException("Monto invalido: " + s.montoCentavos);
        validar(s);

        EstadoPago previo = estados.putIfAbsent(s.idTransaccion, EstadoPago.PENDIENTE);
        if (previo != null)
            return new RespuestaPago(s.idTransaccion, previo,
                    metodo() + ": solicitud duplicada, sin nuevo cobro");

        log("Solicitud " + s.idTransaccion + " -> " + traducirAFormatoProveedor(s));

        if (s.tokenMedioPago.startsWith("tok_caida")) {
            dormir(5_000);
            return new RespuestaPago(s.idTransaccion, EstadoPago.PENDIENTE, metodo() + ": respuesta tardia");
        }

        long retardo = retardoConfirmacionMs();
        PLANIFICADOR.schedule(() -> confirmar(s, callback), retardo, TimeUnit.MILLISECONDS);
        return new RespuestaPago(s.idTransaccion, EstadoPago.PENDIENTE,
                metodo() + ": solicitud aceptada, confirmacion asincrona en ~" + retardo + " ms");
    }

    @Override
    public EstadoPago consultarEstado(String idTransaccion, Current current) {
        return estados.getOrDefault(idTransaccion, EstadoPago.FALLIDO);
    }

    private void confirmar(SolicitudPago s, NotificadorPagosPrx callback) {
        boolean rechazado = s.tokenMedioPago.startsWith("tok_rechazo");
        EstadoPago fin = rechazado ? EstadoPago.RECHAZADO : EstadoPago.APROBADO;
        estados.put(s.idTransaccion, fin);
        String ref = metodo() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String detalle = rechazado
                ? metodo() + ": rechazado (fondos insuficientes)"
                : metodo() + ": aprobado";
        enviarCallback(new ResultadoPago(s.idTransaccion, ref, fin, detalle), callback, new AtomicInteger(1));
    }

    private void enviarCallback(ResultadoPago r, NotificadorPagosPrx cb, AtomicInteger intento) {
        cb.notificarResultadoAsync(r).whenComplete((ok, err) -> {
            if (err == null) {
                log("Callback entregado: " + r.idTransaccion + " = " + r.estado);
            } else if (intento.get() < MAX_INTENTOS_CALLBACK) {
                int n = intento.incrementAndGet();
                log("Callback fallido (intento " + n + "), reintentando: " + err);
                PLANIFICADOR.schedule(() -> enviarCallback(r, cb, intento), 1000L * n, TimeUnit.MILLISECONDS);
            } else {
                log("Callback NO entregado: " + r.idTransaccion);
            }
        });
    }

    protected static long aleatorioEntre(long min, long max) {
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }
    protected void log(String m) { System.out.println("[NODO 3][" + metodo() + "] " + m); }
    private static void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}