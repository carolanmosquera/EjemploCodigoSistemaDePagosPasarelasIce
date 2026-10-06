# ApexStore ICE

Simulación distribuida del proceso de **checkout y pagos** de una tienda en línea (ApexStore), construida con **ZeroC Ice 3.7.10** y **Java**.

El sistema se divide en cuatro nodos que se ejecutan como procesos independientes y se comunican por red mediante Ice. Un cliente crea órdenes de compra y las paga con distintos medios: tarjeta, PSE, criptomoneda y billetera digital. El backend delega cada cobro en la pasarela correspondiente y responde de inmediato con un acuse `PENDIENTE`. Cuando la pasarela confirma el pago, lo avisa al backend de forma asíncrona mediante un *callback*, y el nodo de datos guarda el estado de cada orden y transacción.

El proyecto aplica varios patrones de diseño y de tolerancia a fallos:

- **Strategy:** cada medio de pago es una estrategia que implementa una única interfaz común, `EstrategiaPago`.
- **Callback asíncrono:** las pasarelas confirman los pagos más tarde a través de `NotificadorPagos`.
- **Repositorio abstracto:** el backend no conoce cómo se almacenan los datos.
- **Circuit Breaker y timeouts:** una pasarela caída no bloquea a las demás.
- **Idempotencia:** el sistema impide cobrar dos veces la misma orden.
- **Despacho asíncrono (AMD):** el backend atiende muchos pagos concurrentes sin bloquear hilos.

---

## Estructura del proyecto

```
apexstore-ice/
├── build.gradle              # Compilación, generación de código Slice y tareas run*
├── settings.gradle
├── README.md
├── slice/
│   └── ApexStore.ice         # Contrato Ice: tipos, excepciones e interfaces remotas
├── config/                   # Configuración Ice de cada nodo (endpoints, proxies, parámetros)
│   ├── backend.cfg
│   ├── datos.cfg
│   ├── pasarelas.cfg
│   └── cliente.cfg
└── src/main/java/apexstore/
    ├── backend/              # Nodo 2: fachada de compras y orquestación de pagos
    ├── cliente/              # Nodo 1: cliente de demostración
    ├── datos/                # Nodo 4: repositorio de órdenes y transacciones
    └── pasarelas/            # Nodo 3: pasarelas de pago simuladas
```

### Contrato Slice (`slice/ApexStore.ice`)

El archivo `.ice` es el contrato compartido por todos los nodos. Al compilar, `slice2java` genera a partir de él las clases Java del paquete `apexstore`, como `GestionComprasPrx`, `EstrategiaPago` y `Orden`. Define lo siguiente:

- **Tipos de datos:**
  - `Orden`, `ItemOrden`, `SolicitudPago`, `RespuestaPago`, `ResultadoPago`, `EstadoOrden`.
  - El enum `EstadoPago`: `PENDIENTE`, `APROBADO`, `RECHAZADO`, `FALLIDO` y `PORREVERSAR`.
- **Excepciones:**
  - `PagoException` y sus subtipos `PasarelaNoDisponibleException` y `MetodoNoSoportadoException`.
  - `NoEncontradoException` y `OrdenInvalidaException`.
- **Interfaces remotas:**

| Interfaz | La implementa | La usa | Para qué sirve |
|---|---|---|---|
| `GestionCompras` | Backend | Cliente | Crear órdenes, pagarlas y consultar su estado |
| `EstrategiaPago` | Pasarelas | Backend | Procesar un pago con un medio concreto |
| `NotificadorPagos` | Backend | Pasarelas | Recibir el resultado final de un pago (callback) |
| `RepositorioTransacciones` | Datos | Backend | Guardar y consultar órdenes y transacciones |

---

## Los 4 módulos

### 1. `cliente` (Nodo 1)

Cliente de consola que simula a los compradores. Se conecta únicamente al backend.

| Clase | Qué hace |
|---|---|
| `ClienteApexStore` | Ejecuta una demostración con 7 escenarios: pagos con tarjeta, PSE, cripto y billetera; una tarjeta rechazada; un doble pago de la misma orden; y una caída de PSE que muestra el timeout, la apertura del circuit breaker y que los demás medios siguen funcionando. Al final consulta cada orden hasta conocer su estado definitivo. Con la opción `--carga N` lanza en cambio N pagos PSE concurrentes y mide la latencia (P50, P95 y máximo). |

### 2. `backend` (Nodo 2)

Fachada del sistema: es el único punto de entrada para el cliente. Valida las órdenes, decide qué pasarela usar y coordina el registro de cada pago.

| Clase | Qué hace |
|---|---|
| `ServidorBackend` | Punto de entrada (`main`). Lee `backend.cfg`, crea el adaptador `Backend` en el puerto 10000, obtiene el proxy al repositorio, construye el registro de estrategias y el procesador de pagos, y publica los objetos `checkout` y `notificador-pagos`. |
| `ServicioCheckoutI` | Implementa `GestionCompras`. `crearOrden` valida los ítems, calcula el total y guarda la orden en el repositorio. `pagarOrden` es asíncrona (AMD): recupera la orden y delega el cobro en `ProcesadorPagos` sin bloquear el hilo de Ice. `consultarEstadoOrden` reenvía la consulta al repositorio. |
| `ProcesadorPagos` | Interfaz local, no definida en Slice, que desacopla el servicio de checkout de la lógica de pagos (inversión de dependencias). |
| `ProcesadorPagosContexto` | Implementa `ProcesadorPagos` y es el *contexto* del patrón Strategy. Procesa cada pago en este orden: (1) busca la estrategia del medio elegido; (2) consulta su circuit breaker; (3) registra la transacción en el repositorio, que impide el doble pago; (4) llama a la pasarela con un timeout de 1,5 s. Si la pasarela no responde a tiempo, marca la transacción como `FALLIDO`, registra el fallo en el breaker y devuelve `PasarelaNoDisponibleException`. |
| `RegistroEstrategias` | Lee de `backend.cfg` la lista de medios (`Estrategias.Metodos`) y el proxy de cada uno, y les asigna un `CircuitBreaker`. Agregar un medio de pago nuevo solo requiere añadir una línea de configuración. |
| `CircuitBreaker` | Interruptor con tres estados por pasarela: `CERRADO` (funciona normal), `ABIERTO` (tras 3 fallos seguidos, rechaza las llamadas al instante) y `SEMIABIERTO` (tras 15 s de enfriamiento, deja pasar una llamada de prueba). |
| `ReceptorNotificacionesI` | Implementa `NotificadorPagos`. Recibe el callback de la pasarela con el resultado final del pago y lo aplica en el repositorio. Si el resultado está duplicado o llega tarde, el repositorio lo ignora. |

### 3. `pasarelas` (Nodo 3)

Simula los proveedores de pago externos. Cada medio es un objeto Ice distinto dentro del mismo servidor.

| Clase | Qué hace |
|---|---|
| `ServidorPasarelas` | Punto de entrada (`main`). Lee `pasarelas.cfg`, crea el adaptador `Pasarelas` en el puerto 10001 y publica las cuatro estrategias con las identidades `estrategia-tarjeta`, `estrategia-pse`, `estrategia-cripto` y `estrategia-billetera`. |
| `EstrategiaPagoBase` | Clase abstracta (*Template Method*) que implementa `EstrategiaPago`. En `procesarPago` valida el token y el monto, evita reprocesar una transacción repetida y responde `PENDIENTE` de inmediato. Después programa la confirmación, que envía por callback al backend con hasta 3 reintentos. Los tokens que empiezan por `tok_caida` simulan una pasarela que tarda 5 s en responder, y los que empiezan por `tok_rechazo` simulan fondos insuficientes. |
| `EstrategiaTarjetaSimulada` | Tarjeta de crédito: rechaza números de tarjeta (PAN) en claro y solo acepta tokens, como exige PCI-DSS. Convierte el monto a USD y confirma en 0,3 a 1,2 s. |
| `EstrategiaPSESimulada` | Transferencia bancaria PSE: solo opera en COP y confirma en 3 a 8 s, porque el banco es lento. |
| `EstrategiaCriptoSimulada` | Criptomoneda: convierte el monto a satoshis (testnet simulada) y confirma en 4 a 9 s. |
| `EstrategiaBilleteraSimulada` | Billetera digital: medio agregado después sin modificar el backend. Confirma en 0,5 a 1,5 s. |

### 4. `datos` (Nodo 4)

Capa de persistencia. Guarda en memoria las órdenes y las transacciones, e imprime un registro de auditoría por cada evento.

| Clase | Qué hace |
|---|---|
| `ServidorDatos` | Punto de entrada (`main`). Lee `datos.cfg`, crea el adaptador `Datos` en el puerto 10003 y publica el objeto `repositorio`. |
| `RepositorioTransaccionesI` | Implementa `RepositorioTransacciones` con mapas en memoria y métodos `synchronized`. Su función principal es garantizar la consistencia de los pagos: **(1)** no acepta un pago nuevo si la orden ya está pagada o tiene otro pago en curso; **(2)** ignora los resultados duplicados; **(3)** si un pago marcado `FALLIDO` por timeout llega después como `APROBADO`, lo acepta; **(4)** si se aprueba un segundo cobro para una orden ya pagada, lo marca `PORREVERSAR`. Cada evento se imprime como `[AUDITORIA]`. |

---

## Comunicación entre los nodos

Todos los nodos se comunican por **TCP mediante Ice**, usando los proxies e identidades definidos en `config/`:

```
                     GestionCompras                    EstrategiaPago
  ┌──────────┐   checkout:10000      ┌──────────┐   estrategia-*:10001   ┌────────────┐
  │ CLIENTE  │ ────────────────────► │ BACKEND  │ ─────────────────────► │ PASARELAS  │
  │ (Nodo 1) │ ◄──── acuse ───────── │ (Nodo 2) │ ◄───── callback ────── │  (Nodo 3)  │
  └──────────┘                       └────┬─────┘  NotificadorPagos      └────────────┘
                                          │        notificador-pagos:10000
                                          │ RepositorioTransacciones
                                          │ repositorio:10003
                                          ▼
                                     ┌──────────┐
                                     │  DATOS   │
                                     │ (Nodo 4) │
                                     └──────────┘
```

| Nodo | Adaptador | Puerto | Objetos que publica |
|---|---|---|---|
| Backend | `Backend` | 10000 | `checkout`, `notificador-pagos` |
| Pasarelas | `Pasarelas` | 10001 | `estrategia-tarjeta`, `estrategia-pse`, `estrategia-cripto`, `estrategia-billetera` |
| Datos | `Datos` | 10003 | `repositorio` |

### Flujo de un pago

1. **Cliente → Backend:** `crearOrden(...)`. El backend valida la orden y la guarda en Datos con `guardarOrden`.
2. **Cliente → Backend:** `pagarOrden(idOrden, "TARJETA", token)`.
3. **Backend → Datos:** `registrarTransaccion(...)`. Datos rechaza el pago si la orden ya está pagada o tiene otro pago en curso.
4. **Backend → Pasarelas:** `procesarPago(solicitud, callback)`. El backend envía su propio proxy `notificador-pagos` como callback y espera la respuesta como máximo 1,5 s.
5. **Pasarelas → Backend → Cliente:** la pasarela responde `PENDIENTE` de inmediato y el backend reenvía ese acuse al cliente.
6. **Pasarelas → Backend (callback):** segundos después, la pasarela llama a `notificarResultado(resultado)` con `APROBADO` o `RECHAZADO`.
7. **Backend → Datos:** `aplicarResultado(resultado)` actualiza el estado de la orden.
8. **Cliente → Backend → Datos:** `consultarEstadoOrden(idOrden)` devuelve el estado final.

Si la pasarela no responde a tiempo, el backend marca la transacción como `FALLIDO` y el cliente recibe `PasarelaNoDisponibleException`. Tras 3 fallos seguidos, el circuit breaker de ese medio se abre y rechaza los pagos siguientes al instante, mientras los demás medios siguen funcionando. El nodo de Pasarelas usa varios hilos de despacho (`Ice.ThreadPool.Server.SizeMax=16`) para que una pasarela lenta no bloquee a las otras.

---

## Cómo ejecutar el proyecto

### Requisitos

- **JDK 17 o superior.** El código se compila con compatibilidad para Java 11 (`options.release = 11`).
- **Gradle** (probado con 9.0).
- **ZeroC Ice 3.7.10** instalado, con `slice2java` en el `PATH`. También se puede definir la variable de entorno `ICE_HOME` con la carpeta de instalación de Ice. Las librerías Java de Ice se descargan automáticamente desde Maven Central.

### Compilar

Desde la raíz del proyecto:

```
gradle clean build
```

Este comando genera el código Java desde `slice/ApexStore.ice` en `build/generated-slice` y luego compila todo el proyecto.

### Ejecutar

Cada nodo es un proceso independiente, así que se necesita **una terminal por nodo**. Arráncalos **en este orden**:

```
# Terminal 1: Nodo 4, datos
gradle runDatos

# Terminal 2: Nodo 3, pasarelas
gradle runPasarelas

# Terminal 3: Nodo 2, backend (necesita Datos y Pasarelas activos)
gradle runBackend

# Terminal 4: Nodo 1, cliente (necesita el Backend activo)
gradle runCliente
```

Para ejecutar la prueba de carga en lugar de la demostración (por ejemplo, con 50 pagos PSE concurrentes):

```
gradle runCliente -Pcarga=50
```

### Qué esperar

- **Servidores:** muestran un mensaje como `[NODO 4] Repositorio activo en tcp -h 127.0.0.1 -p 10003` y se quedan esperando peticiones. Gradle muestra `EXECUTING` de forma indefinida; esto es normal. Se detienen con **Ctrl+C**.
- **Cliente:** recorre los 7 escenarios, imprime el resultado de cada pago y termina con una tabla del estado final de cada orden.

### Configuración

Los puertos, los timeouts y los parámetros del circuit breaker se cambian en `config/*.cfg`, sin recompilar:

| Propiedad (`backend.cfg`) | Valor por defecto | Significado |
|---|---|---|
| `Pagos.TimeoutAcuseMs` | 1500 | Tiempo máximo de espera del acuse de una pasarela |
| `Pagos.Breaker.UmbralFallos` | 3 | Fallos seguidos necesarios para abrir el breaker |
| `Pagos.Breaker.EnfriamientoMs` | 15000 | Tiempo que el breaker permanece abierto antes de probar de nuevo |
| `Pagos.HilosPosproceso` | 8 | Hilos para procesar las respuestas de las pasarelas |
| `Estrategias.Metodos` | `TARJETA,PSE,CRIPTO,BILLETERA` | Medios de pago habilitados (cada uno necesita su `Estrategia.<MEDIO>.Proxy`) |
