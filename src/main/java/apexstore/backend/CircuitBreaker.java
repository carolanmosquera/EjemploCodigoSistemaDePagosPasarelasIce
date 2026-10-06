package apexstore.backend;

public final class CircuitBreaker {

    public enum Estado { CERRADO, ABIERTO, SEMIABIERTO }

    private final String nombre;
    private final int umbral;
    private final long enfriamientoMs;

    private Estado estado = Estado.CERRADO;
    private int fallos;
    private long abiertoDesde;

    public CircuitBreaker(String nombre, int umbral, long enfriamientoMs) {
        this.nombre = nombre; this.umbral = umbral; this.enfriamientoMs = enfriamientoMs;
    }

    public synchronized boolean permiteLlamada() {
        if (estado == Estado.ABIERTO) {
            if (System.currentTimeMillis() - abiertoDesde >= enfriamientoMs) {
                estado = Estado.SEMIABIERTO;
                System.out.println("[BREAKER " + nombre + "] SEMIABIERTO");
                return true;
            }
            return false;
        }
        return true;
    }
    public synchronized void registrarExito() {
        if (estado != Estado.CERRADO) System.out.println("[BREAKER " + nombre + "] CERRADO");
        fallos = 0; estado = Estado.CERRADO;
    }
    public synchronized void registrarFallo() {
        fallos++;
        if (estado == Estado.SEMIABIERTO || fallos >= umbral) {
            if (estado != Estado.ABIERTO)
                System.out.println("[BREAKER " + nombre + "] ABIERTO tras " + fallos + " fallos");
            estado = Estado.ABIERTO;
            abiertoDesde = System.currentTimeMillis();
        }
    }
    public synchronized Estado estado() { return estado; }
}