package apexstore.pasarelas;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

public final class ServidorPasarelas {
    private ServidorPasarelas() {}
    public static void main(String[] args) {
        try (Communicator c = Util.initialize(args, "config/pasarelas.cfg")) {
            ObjectAdapter a = c.createObjectAdapter("Pasarelas");
            a.add(new EstrategiaTarjetaSimulada(),   Util.stringToIdentity("estrategia-tarjeta"));
            a.add(new EstrategiaPSESimulada(),       Util.stringToIdentity("estrategia-pse"));
            a.add(new EstrategiaCriptoSimulada(),    Util.stringToIdentity("estrategia-cripto"));
            a.add(new EstrategiaBilleteraSimulada(), Util.stringToIdentity("estrategia-billetera"));
            a.activate();
            System.out.println("[NODO 3] Pasarelas activas en "
                    + c.getProperties().getProperty("Pasarelas.Endpoints"));
            c.waitForShutdown();
        }
    }
}