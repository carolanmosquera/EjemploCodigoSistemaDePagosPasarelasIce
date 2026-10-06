module apexstore {

  enum EstadoPago { PENDIENTE, APROBADO, RECHAZADO, FALLIDO, PORREVERSAR };

  struct ItemOrden {
    string sku;
    int    cantidad;
    long   precioUnitarioCentavos;
  };

  sequence<ItemOrden> ItemOrdenSeq;

  struct Orden {
    string       idOrden;
    string       idCliente;
    ItemOrdenSeq items;
    long         totalCentavos;
    string       moneda;
  };

  struct SolicitudPago {
    string idTransaccion;
    string idOrden;
    long   montoCentavos;
    string moneda;
    string metodo;
    string tokenMedioPago;
  };

  struct ResultadoPago {
    string     idTransaccion;
    string     referenciaExterna;
    EstadoPago estado;
    string     detalle;
  };

  struct RespuestaPago {
    string     idTransaccion;
    EstadoPago estado;
    string     mensaje;
  };

  struct EstadoOrden {
    string     idOrden;
    string     idTransaccion;
    EstadoPago estadoPago;
    string     detalle;
  };

  // === Excepciones uniformes (traducción de errores) ===
  exception PagoException                 { string razon; };
  exception NoEncontradoException         { string id; };
  exception OrdenInvalidaException        { string razon; };
  exception MetodoNoSoportadoException    extends PagoException {};
  exception PasarelaNoDisponibleException extends PagoException {};

  // === Callback uniforme (corrige el hallazgo 1.4) ===
  interface NotificadorPagos {
    void notificarResultado(ResultadoPago resultado);
  };

  // === Strategy con UNA sola interfaz común (corrige el hallazgo 1.3) ===
  interface EstrategiaPago {
    idempotent RespuestaPago procesarPago(SolicitudPago solicitud, NotificadorPagos* callback)
        throws PagoException;
    idempotent EstadoPago consultarEstado(string idTransaccion);
  };

  // === Repositorio abstracto (sin nombrar Postgres) ===
  interface RepositorioTransacciones {
    void         guardarOrden(Orden orden);
    Orden        obtenerOrden(string idOrden)            throws NoEncontradoException;
    bool         registrarTransaccion(string idTransaccion, string idOrden, string metodo,
                                      long montoCentavos, string moneda)
                                                          throws NoEncontradoException;
    bool         aplicarResultado(ResultadoPago resultado);
    EstadoOrden  consultarEstadoOrden(string idOrden)    throws NoEncontradoException;
  };

  // === Fachada del Nodo 2 (único punto de entrada desde Internet) ===
  interface GestionCompras {
    string crearOrden(string idCliente, ItemOrdenSeq items, string moneda)
        throws OrdenInvalidaException;

    ["amd"] RespuestaPago pagarOrden(string idOrden, string metodo, string tokenMedioPago)
        throws PagoException;

    EstadoOrden consultarEstadoOrden(string idOrden)
        throws NoEncontradoException;
  };

};