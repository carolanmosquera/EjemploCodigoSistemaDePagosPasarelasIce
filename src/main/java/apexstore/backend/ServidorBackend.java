package apexstore.backend;

import apexstore.NotificadorPagosPrx;
import apexstore.RepositorioTransaccionesPrx;
import com.zeroc.Ice.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ServidorBackend {
    private ServidorBackend() {}

    public static void main(String[] args) {
        try (Communicator c = Util.initialize(args, "config/backend.cfg")) {
            Properties p = c.getProperties();

            RepositorioTransaccionesPrx repositorio =
                    RepositorioTransaccionesPrx.uncheckedCast(c.propertyToProxy("Repositorio.Proxy"));
            if (repositorio == null)
                throw new IllegalStateException("Falta Repositorio.Proxy");

            ObjectAdapter adapter = c.createObjectAdapter("Backend");
            NotificadorPagosPrx notificador = NotificadorPagosPrx.uncheckedCast(
                    adapter.add(new ReceptorNotificacionesI(repositorio),
                            Util.stringToIdentity("notificador-pagos")));

            RegistroEstrategias registro = RegistroEstrategias.desdeConfiguracion(c);
            ExecutorService ejecutor = Executors.newFixedThreadPool(
                    p.getPropertyAsIntWithDefault("Pagos.HilosPosproceso", 8));

            ProcesadorPagos procesador = new ProcesadorPagosContexto(registro, repositorio, notificador,
                    p.getPropertyAsIntWithDefault("Pagos.TimeoutAcuseMs", 1_500), ejecutor);

            adapter.add(new ServicioCheckoutI(repositorio, procesador), Util.stringToIdentity("checkout"));
            adapter.activate();
            System.out.println("[NODO 2] Backend activo en " + p.getProperty("Backend.Endpoints")
                    + " | medios: " + registro.metodosSoportados());
            c.waitForShutdown();
            ejecutor.shutdown();
        }
    }
}