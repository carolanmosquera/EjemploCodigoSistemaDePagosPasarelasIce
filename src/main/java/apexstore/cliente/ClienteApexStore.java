package apexstore.cliente;

import apexstore.*;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Util;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class ClienteApexStore {
    private ClienteApexStore() {}

    public static void main(String[] args) throws Exception {
        try (Communicator c = Util.initialize(args, "config/cliente.cfg")) {
            GestionComprasPrx compras = GestionComprasPrx.checkedCast(c.propertyToProxy("Checkout.Proxy"));
            if (compras == null) throw new IllegalStateException("No se encontro el checkout");

            int carga = leerCarga(args);
            if (carga > 0) { pruebaDeCarga(compras, carga); return; }

            Map<String, String> ordenes = new LinkedHashMap<>();

            titulo("1. Pago con tarjeta (token, sin PAN ni CVC)");
            pagar(compras, ordenes, "TARJETA", "tok_visa_4242");

            titulo("2. Pago PSE (banco lento, backend responde de inmediato)");
            pagar(compras, ordenes, "PSE", "tok_pse_bancolombia");

            titulo("3. Pago cripto (confirma por callback, ya no escribe en BD)");
            pagar(compras, ordenes, "CRIPTO", "tok_wallet_bc1q");

            titulo("4. Billetera digital (medio NUEVO sin tocar el contexto)");
            pagar(compras, ordenes, "BILLETERA", "tok_billetera_3001234567");

            titulo("5. Tarjeta rechazada");
            pagar(compras, ordenes, "TARJETA", "tok_rechazo_fondos");

            titulo("6. Doble pago de la misma orden");
            String idO = compras.crearOrden("cli-6", carrito(), "COP");
            intentarPago(compras, idO, "TARJETA", "tok_visa_1111");
            intentarPago(compras, idO, "TARJETA", "tok_visa_1111");
            ordenes.put(idO, "doble-pago");

            titulo("7. PSE caido: timeout, breaker y aislamiento");
            for (int i = 1; i <= 3; i++) pagar(compras, ordenes, "PSE", "tok_caida_" + i);
            pagar(compras, ordenes, "PSE", "tok_pse_davivienda");
            pagar(compras, ordenes, "TARJETA", "tok_mastercard_5555");

            titulo("Seguimiento de estados");
            seguirEstados(compras, ordenes, 20_000);
        }
    }

    private static void pagar(GestionComprasPrx c, Map<String,String> seg, String metodo, String token) throws Exception {
        String id = c.crearOrden("cliente-demo", carrito(), "COP");
        seg.put(id, metodo);
        intentarPago(c, id, metodo, token);
    }

    private static void intentarPago(GestionComprasPrx c, String id, String metodo, String token) {
        long t0 = System.nanoTime();
        try {
            RespuestaPago r = c.pagarOrden(id, metodo, token);
            System.out.printf("   %s %-9s -> %s en %d ms | %s%n", id, metodo, r.estado,
                    (System.nanoTime()-t0)/1_000_000, r.mensaje);
        } catch (PagoException e) {
            System.out.printf("   %s %-9s -> RECHAZADO en %d ms | %s: %s%n", id, metodo,
                    (System.nanoTime()-t0)/1_000_000, e.getClass().getSimpleName(), e.razon);
        }
    }

    private static void seguirEstados(GestionComprasPrx c, Map<String,String> ordenes, long maxMs) throws Exception {
        long limite = System.currentTimeMillis() + maxMs;
        List<String> pend = new ArrayList<>(ordenes.keySet());
        while (!pend.isEmpty() && System.currentTimeMillis() < limite) {
            Thread.sleep(1000);
            pend.removeIf(id -> {
                try {
                    EstadoOrden eo = c.consultarEstadoOrden(id);
                    return eo.estadoPago != EstadoPago.PENDIENTE || eo.idTransaccion.isEmpty();
                } catch (Exception e) { return true; }
            });
        }
        for (Map.Entry<String,String> e : ordenes.entrySet()) {
            EstadoOrden eo = c.consultarEstadoOrden(e.getKey());
            String etq = eo.idTransaccion.isEmpty() ? "SIN PAGO" : eo.estadoPago.toString();
            System.out.printf("   %s %-10s -> %-16s %s%n", e.getKey(), e.getValue(), etq, eo.detalle);
        }
    }

    private static void pruebaDeCarga(GestionComprasPrx c, int n) throws Exception {
        titulo("Carga: " + n + " pagos PSE concurrentes (banco 3-8 s)");
        String[] ids = new String[n];
        for (int i = 0; i < n; i++) ids[i] = c.crearOrden("carga-"+i, carrito(), "COP");

        long[] lat = new long[n];
        CompletableFuture<?>[] fs = new CompletableFuture<?>[n];
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            final int k = i; final long ini = System.nanoTime();
            fs[i] = c.pagarOrdenAsync(ids[i], "PSE", "tok_pse_carga_" + i)
                    .whenComplete((r, e) -> lat[k] = (System.nanoTime()-ini)/1_000_000);
        }
        CompletableFuture.allOf(fs).exceptionally(e -> null).join();
        long total = (System.nanoTime()-t0)/1_000_000;
        Arrays.sort(lat);
        System.out.printf("   %d acuses en %d ms | P50=%d | P95=%d | max=%d%n",
                n, total, lat[n/2], lat[(int)Math.ceil(n*0.95)-1], lat[n-1]);
    }

    private static int leerCarga(String[] args) {
        for (int i = 0; i < args.length - 1; i++)
            if ("--carga".equals(args[i])) return Integer.parseInt(args[i+1]);
        return 0;
    }

    private static ItemOrden[] carrito() {
        return new ItemOrden[] {
            new ItemOrden("SKU-AUDIFONOS-01", 1, 25_000_000L),
            new ItemOrden("SKU-CABLE-USBC",   2,  2_500_000L)
        };
    }

    private static void titulo(String t) { System.out.println("\n== " + t); }
}