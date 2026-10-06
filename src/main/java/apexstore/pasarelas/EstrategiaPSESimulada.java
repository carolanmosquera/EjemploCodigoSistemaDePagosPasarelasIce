// EstrategiaPSESimulada.java
package apexstore.pasarelas;

import apexstore.PagoException;
import apexstore.SolicitudPago;

public class EstrategiaPSESimulada extends EstrategiaPagoBase {
    @Override protected String metodo() { return "PSE"; }
    @Override protected void validar(SolicitudPago s) throws PagoException {
        if (!"COP".equals(s.moneda)) throw new PagoException("PSE solo opera en COP");
    }
    @Override protected String traducirAFormatoProveedor(SolicitudPago s) {
        return String.format("debitarTransferencia(ref=%s, valorCOP=%d)",
                s.tokenMedioPago, s.montoCentavos/100);
    }
    @Override protected long retardoConfirmacionMs() { return aleatorioEntre(3_000, 8_000); }
}