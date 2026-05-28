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

## Nota
- Dependencia local usada: `lib\escpos-coffee-4.1.0.jar` (embebida dentro de `dist\TicketPrinterServer.jar` al compilar).
- El servicio guarda la impresora elegida en `selected-printer.txt` en la misma carpeta.
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
