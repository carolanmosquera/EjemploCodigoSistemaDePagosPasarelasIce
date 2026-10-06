// EstrategiaCriptoSimulada.java
package apexstore.pasarelas;

import apexstore.PagoException;
import apexstore.SolicitudPago;

public class EstrategiaCriptoSimulada extends EstrategiaPagoBase {
    private static final double SATOSHIS_POR_COP = 0.004;
    @Override protected String metodo() { return "CRIPTO"; }
    @Override protected void validar(SolicitudPago s) throws PagoException {
        if (!s.tokenMedioPago.startsWith("tok_"))
            throw new PagoException("Referencia cripto invalida");
    }
    @Override protected String traducirAFormatoProveedor(SolicitudPago s) {
        long sat = Math.round((s.montoCentavos/100.0) * SATOSHIS_POR_COP);
        return String.format("generarCobro(wallet=%s, red=bitcoin-testnet-sim, satoshis=%d)",
                s.tokenMedioPago, sat);
    }
    @Override protected long retardoConfirmacionMs() { return aleatorioEntre(4_000, 9_000); }
}