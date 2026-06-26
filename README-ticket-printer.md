# Ticket Printer (ESC/POS) local

Servicio Java para imprimir tickets ESC/POS por HTTP local.
Incluye UI web, UI nativa y System Tray en Windows.
Motor de impresion: `escpos-coffee`.

## Endpoint base
- `http://127.0.0.1:5001`

## Flujo rapido
1. Compilar:
```powershell
.\build-server.ps1
```

2. Iniciar en segundo plano:
```powershell
.\run-server-background.ps1
```

2.1 Iniciar con UI de Windows/System Tray:
```powershell
java -jar .\dist\TicketPrinterServer.jar
```

3. Ver impresoras instaladas:
```powershell
Invoke-RestMethod http://127.0.0.1:5001/printers
```

4. Seleccionar impresora:
```powershell
$body = @{ name = "NOMBRE_DE_TU_IMPRESORA" } | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:5001/printer/select -Method Post -ContentType "application/json" -Body $body
```

5. Imprimir ticket (POST requerido por ti):
```powershell
$ticket = @{
  ticketType = "nota_venta"
  ticketWidth = 42
  designVersion = "v1"
  lines = @("TIENDA XYZ", "Ticket #1001", "Total: $123.45", "Gracias")
  align = "left"
  bold = $false
  feed = 3
  cut = $true
  drawer = $false
  charset = "Cp850"
} | ConvertTo-Json

Invoke-RestMethod http://127.0.0.1:5001/printer -Method Post -ContentType "application/json" -Body $ticket
```

6. Detener servicio:
```powershell
.\stop-server.ps1
```

## API
- `GET /health`
- `GET /printers` -> lista de impresoras + seleccionada
- `GET /queue` -> cola de impresion de la impresora seleccionada
- `POST /printer/select` body: `{ "name": "Mi Impresora" }`
- `POST /printer` body JSON del ticket
- `GET /label/brother/config` -> configuracion de etiquetas Brother
- `POST /label/brother/config` -> guarda impresora y carpeta de plantillas Brother
- `POST /label/brother` body JSON para etiquetas Brother por SDK b-PAC

## JSON soportado en /printer
- `ticketType`: `nota_venta` o `factura`
- `ticketWidth`: recomendado `32`, `42` o `48` (tambien acepta otros valores)
- `designVersion`: `v1` o `v2`/`compact`
- `lines`: arreglo de renglones
- `text`: texto unico (alternativa a lines)
- `align`: `left|center|right`
- `bold`: `true|false`
- `feed`: lineas extra al final
- `cut`: cortar ticket
- `drawer`: abrir cajon
- `charset`: por defecto `Cp850`
- `qrSize`: tamano del QR (3 a 10, default 6)
- `qrErrorLevel`: `L|M|Q|H` (default `M`)
- `rawBase64`: bytes ESC/POS directos en base64 (si viene, se imprime tal cual)
- `data`: objeto de factura (cuando `ticketType = factura`)

### Nota de venta estructurada
Para `ticketType = nota_venta`, tambien soporta objeto estructurado en `data`:
- `data.Cliente`
- `data.Venta`
- `data.Venta.Productos`

### Factura con QR nativo ESC/POS
- Si `ticketType = factura` y llega `data.Venta.Qr`, se imprime el QR de forma nativa en ESC/POS.

## UI local para seleccionar impresora
- Abre `http://127.0.0.1:5001/` en navegador para elegir y guardar impresora.
- En Windows tambien puedes usar la ventana nativa del programa para seleccionar impresora, abrir la UI web y arrancar/detener el servicio HTTP.

## System Tray (Windows)
- Iniciar servicio
- Detener servicio
- Mostrar cola de impresion
- Enviar texto de prueba
- Configurar inicio con Windows
- Configurar si el servicio inicia automaticamente al abrir la app

## Etiquetas Brother QL-800 con SDK b-PAC
Este endpoint usa el SDK Brother b-PAC instalado en Windows mediante COM.
La etiqueta se disena en Brother P-touch Editor / b-PAC como plantilla `.lbx`; ahi defines el tamano de papel, orientacion, fuentes, codigos de barra y los nombres de objetos.

Este flujo es separado de `/printer`:
- `/printer` imprime tickets ESC/POS usando la impresora de tickets.
- `/label/brother` imprime etiquetas Brother usando la impresora de etiquetas configurada.

Requisitos:
- Driver de la Brother QL-800 instalado.
- Brother b-PAC SDK instalado.
- Plantilla `.lbx` creada con objetos nombrados, por ejemplo `cliente`, `folio`, `fecha`, `equipo`, `marca`, `modelo`, `serie`, `problema`.

Configurar impresora de etiquetas y carpeta de plantillas:
```powershell
$config = @{
  printerName = "Brother QL-810W"
  templateDir = "C:\Etiquetas"
} | ConvertTo-Json

Invoke-RestMethod http://127.0.0.1:5001/label/brother/config -Method Post -ContentType "application/json" -Body $config
```

Consultar configuracion y plantillas disponibles:
```powershell
Invoke-RestMethod http://127.0.0.1:5001/label/brother/config
```

Imprimir usando solo el nombre de plantilla:
```powershell
$label = @{
  templateName = "mantenimiento"
  copies = 1
  fields = @{
    cliente = "Contador Ivan De Jesus (UPGCH C.COMPRAS)"
    folio = "1713475"
    fecha = "06/04/2026"
    equipo = "LAPTOP"
    marca = "DELL"
    modelo = "INSPIRON"
    serie = "3VW3"
    problema = "EL EQUIPO VA CON UN PROBLEMA EN LA BISAGRA DE DISPLAY"
  }
} | ConvertTo-Json -Depth 5

Invoke-RestMethod http://127.0.0.1:5001/label/brother -Method Post -ContentType "application/json" -Body $label
```

Notas:
- El tamano de etiqueta se controla desde la plantilla `.lbx`, por ejemplo 29x90, 62x29, etc.
- Los nombres dentro de `fields` deben coincidir con los nombres de objetos de la plantilla.
- Tambien puedes enviar `templatePath` si necesitas usar una ruta completa de plantilla.
- Tambien puedes enviar `printerName` si quieres sobreescribir la impresora configurada solo en esa impresion.
- Puedes enviar `ignoreMissingObjects = $true` si quieres ignorar campos que no existan en la plantilla.

## Nota
- Dependencia local usada: `lib\escpos-coffee-4.1.0.jar` (embebida dentro de `dist\TicketPrinterServer.jar` al compilar).
- El servicio guarda la impresora elegida en `%APPDATA%\TicketPrinter\selected-printer.txt`.
- Para instalador `.exe` tipo setup, usa WiX + `jpackage --type exe` o empaqueta este app-image con Inno Setup/NSIS.


////
Para genenera y compilar

cd "C:\Users\Salvador A Cervantes\Documents\Plugin"
.\build-server.ps1

& "C:\Program Files\Java\jdk-26\bin\jpackage.exe" `
  --type app-image `
  --name TicketPrinterServerUI `
  --input dist `
  --main-jar TicketPrinterServer.jar `
  --main-class Main `
  --dest installer `
  --app-version 1.0.8
