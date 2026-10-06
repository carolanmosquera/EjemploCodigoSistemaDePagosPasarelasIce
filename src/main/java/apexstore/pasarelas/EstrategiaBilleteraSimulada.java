// EstrategiaBilleteraSimulada.java
package apexstore.pasarelas;

import apexstore.PagoException;
import apexstore.SolicitudPago;

public class EstrategiaBilleteraSimulada extends EstrategiaPagoBase {
    @Override protected String metodo() { return "BILLETERA"; }
    @Override protected void validar(SolicitudPago s) throws PagoException {
        if (!s.tokenMedioPago.startsWith("tok_"))
            throw new PagoException("Token de billetera invalido");
    }
    @Override protected String traducirAFormatoProveedor(SolicitudPago s) {
        return String.format("cobrarBilletera(alias=%s, valor=%d %s)",
                s.tokenMedioPago, s.montoCentavos/100, s.moneda);
    }
    @Override protected long retardoConfirmacionMs() { return aleatorioEntre(500, 1_500); }
}