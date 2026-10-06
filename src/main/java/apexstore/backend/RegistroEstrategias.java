package apexstore.backend;

import apexstore.EstrategiaPagoPrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Properties;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RegistroEstrategias {

    private final Map<String, EstrategiaPagoPrx> estrategias = new ConcurrentHashMap<>();
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final int umbralFallos;
    private final long enfriamientoMs;

    public RegistroEstrategias(int umbralFallos, long enfriamientoMs) {
        this.umbralFallos = umbralFallos; this.enfriamientoMs = enfriamientoMs;
    }

    public static RegistroEstrategias desdeConfiguracion(Communicator c) {
        Properties p = c.getProperties();
        RegistroEstrategias r = new RegistroEstrategias(
                p.getPropertyAsIntWithDefault("Pagos.Breaker.UmbralFallos", 3),
                p.getPropertyAsIntWithDefault("Pagos.Breaker.EnfriamientoMs", 15_000));

        String[] metodos = p.getProperty("Estrategias.Metodos").split(",");
        for (String m : metodos) {
            String clave = m.trim().toUpperCase();
            ObjectPrx base = c.propertyToProxy("Estrategia." + clave + ".Proxy");
            if (base == null) {
                System.err.println("[BACKEND] Falta proxy para " + clave + "; omitido");
                continue;
            }
            r.registrar(clave, EstrategiaPagoPrx.uncheckedCast(base));
        }
        return r;
    }

    public void registrar(String metodo, EstrategiaPagoPrx estrategia) {
        estrategias.put(metodo, estrategia);
        breakers.put(metodo, new CircuitBreaker(metodo, umbralFallos, enfriamientoMs));
        System.out.println("[BACKEND] Estrategia registrada: " + metodo);
    }
    public EstrategiaPagoPrx obtener(String metodo) { return estrategias.get(metodo); }
    public CircuitBreaker breaker(String metodo) { return breakers.get(metodo); }
    public Set<String> metodosSoportados() { return estrategias.keySet(); }
}