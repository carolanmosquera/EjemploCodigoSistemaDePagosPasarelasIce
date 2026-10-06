// EstrategiaTarjetaSimulada.java
package apexstore.pasarelas;

import apexstore.PagoException;
import apexstore.SolicitudPago;

public class EstrategiaTarjetaSimulada extends EstrategiaPagoBase {
    private static final double TASA_COP_POR_USD = 4_000.0;
    @Override protected String metodo() { return "TARJETA"; }
    @Override protected void validar(SolicitudPago s) throws PagoException {
        if (s.tokenMedioPago.replaceAll("[\\s-]", "").matches("\\d{12,19}"))
            throw new PagoException("Se recibio un PAN en claro; solo se aceptan tokens (PCI-DSS)");
        if (!s.tokenMedioPago.startsWith("tok_"))
            throw new PagoException("Token de tarjeta invalido");
    }
    @Override protected String traducirAFormatoProveedor(SolicitudPago s) {
        double usd = "USD".equals(s.moneda) ? s.montoCentavos/100.0 : (s.montoCentavos/100.0)/TASA_COP_POR_USD;
        return String.format("autorizarCargo(token=%s, montoUSD=%.2f)", s.tokenMedioPago, usd);
    }
    @Override protected long retardoConfirmacionMs() { return aleatorioEntre(300, 1_200); }
}