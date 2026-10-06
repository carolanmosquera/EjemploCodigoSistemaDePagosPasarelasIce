package apexstore.datos;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

public final class ServidorDatos {
    private ServidorDatos() {}

    public static void main(String[] args) {
        try (Communicator communicator = Util.initialize(args, "config/datos.cfg")) {
            ObjectAdapter adapter = communicator.createObjectAdapter("Datos");
            adapter.add(new RepositorioTransaccionesI(), Util.stringToIdentity("repositorio"));
            adapter.activate();
            System.out.println("[NODO 4] Repositorio activo en "
                    + communicator.getProperties().getProperty("Datos.Endpoints"));
            communicator.waitForShutdown();
        }
    }
}